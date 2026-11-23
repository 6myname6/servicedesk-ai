package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.domain.ticket.TicketScope;
import com.servicedesk.dto.request.ticket.TicketAssignRequest;
import com.servicedesk.dto.request.ticket.TicketCancelRequest;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.dto.request.ticket.TicketFeedbackRequest;
import com.servicedesk.dto.request.ticket.TicketQueryRequest;
import com.servicedesk.dto.request.ticket.TicketUpdateRequest;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.TicketFeedback;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.entity.enums.TicketCategory;
import com.servicedesk.entity.enums.TicketEventType;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.entity.enums.Urgency;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketFeedbackMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.TicketService;
import com.servicedesk.service.support.TicketAccessGuard;
import com.servicedesk.service.support.TicketAssembler;
import com.servicedesk.service.support.TicketAttachEventWriter;
import com.servicedesk.service.support.TicketAttachmentLinker;
import com.servicedesk.service.support.TicketNoGenerator;
import com.servicedesk.service.support.TicketScopeResolver;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketFeedbackVO;
import com.servicedesk.vo.TicketListItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 工单服务实现（建单 T-01、列表 T-02、详情 T-03、编辑 T-04、取消 T-05、评价 T-06、派单 F-01）。
 *
 * <p>
 * 状态迁移（F-02）不在这里：五个流转动作的角色与时间戳副作用集中在
 * {@code TicketTransitionServiceImpl}，本类只保留建单 / 查询 / 编辑 / 取消 / 评价 / 派单，
 * 避免继续膨胀（编码规范 7.1）。
 * </p>
 *
 * <p>
 * 属于业务实现层。建单主链路：
 * </p>
 * <ol>
 * <li>校验客户、设备归属、分类/紧急度取值与附件可用性——全部在写库前完成；</li>
 * <li>按紧急度匹配启用中的 SLA 策略，用同一基准时刻算出两个 deadline；</li>
 * <li>生成工单号并插入工单（`NEW`、AI 字段留空）；</li>
 * <li>把已上传附件的 {@code ticket_id} 回填为本工单。</li>
 * </ol>
 *
 * <p>
 * 关键约束：工单插入与附件回填在同一个事务内，附件回填失败要连工单一起回滚，
 * 不能留下「工单没有图」的半成品；工单号来自 Redis，回滚不会退号，允许跳号。
 * </p>
 *
 * <p>
 * 详情与编辑共用
 * {@link TicketAssembler#assembleDetail(Ticket, LocalDateTime)}：建单、详情、编辑
 * 三个出口返回同一个 A.8 结构，任何一处新增字段都只改装配器。读路径的时间基准统一由调用方传入，
 * RC-001 后不再有暂停口径，列表与详情都按当前时刻计算倒计时。
 * </p>
 *
 * <p>
 * 错误码分工：不存在与越权统一 404（{@link TicketAccessGuard#requireVisible}），状态不允许与
 * 版本冲突 409，取值非法 400，策略缺失 503。顺序不能调换，否则会通过 409 暴露工单是否存在。
 * </p>
 *
 * <p>
 * 本类只保留编排与「单用例专用」逻辑：查询条件拼装、客户/设备/策略校验、建单实体组装、
 * 重试插号、字段合并。这些方法只被一个用例调用且与编排上下文强耦合，拆成 Bean 只会增加跳转层级。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    /** 工单号唯一冲突时的最大尝试次数（含首次）。 */
    private static final int MAX_INSERT_ATTEMPTS = 2;

    /** 工单 Mapper，建单插入、列表分页、编辑更新都用它。 */
    private final TicketMapper ticketMapper;

    /** 工单号生成器，Redis 日序列 + 库内兜底。 */
    private final TicketNoGenerator ticketNoGenerator;

    /** 用户 Mapper，派单时校验被派人是否存在、启用、角色与团队。 */
    private final SysUserMapper sysUserMapper;

    /** 客户 Mapper，仅用于建单前校验客户存在，装配由 {@link TicketAssembler} 负责。 */
    private final CustomerMapper customerMapper;

    /** 设备 Mapper，仅用于校验设备存在性与归属。 */
    private final DeviceMapper deviceMapper;

    /** SLA 策略 Mapper，仅用于按紧急度匹配启用策略。 */
    private final SlaPolicyMapper slaPolicyMapper;

    /** 工单事件 Mapper，取消等状态迁移追加时间线，只写不改。 */
    private final TicketEventMapper ticketEventMapper;

    /** 工单回访 Mapper，一单一评，唯一索引兜底并发。 */
    private final TicketFeedbackMapper ticketFeedbackMapper;

    /** 数据范围解析器，把登录身份翻译成工单可见范围。 */
    private final TicketScopeResolver ticketScopeResolver;

    /** 业务时钟，建单时刻与剩余分钟都取它，禁止无参 {@code now()}。 */
    private final Clock clock;

    /** 读模型装配器，详情与列表的统一出口。 */
    private final TicketAssembler ticketAssembler;

    /** 访问守卫，负责 404 / 409 的判定顺序。 */
    private final TicketAccessGuard ticketAccessGuard;

    /** 附件关联器，负责校验与回填 {@code ticket_id}。 */
    private final TicketAttachmentLinker ticketAttachmentLinker;

    /** JSON 工具，把编辑变更的字段名写进事件 metadata；不解析业务正文。 */
    private final ObjectMapper objectMapper;

    /** 附件事件写入器，建单挂附件时统一写 ATTACH，避免与删除路径各拼一套 metadata。 */
    private final TicketAttachEventWriter ticketAttachEventWriter;

    /**
     * 创建工单。
     *
     * @param request 建单入参
     * @return 新工单详情
     * @throws BusinessException 客户/设备/分类/紧急度/附件不合法抛 400；
     *                           SLA 策略缺失或工单号连续冲突抛 503
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO create(TicketCreateRequest request) {
        LoginUser operator = UserContext.getRequired();
        Customer customer = requireCustomer(request.getCustomerId(), operator.id());
        Device device = requireDeviceOfCustomer(request.getDeviceId(), customer.getId(), operator.id());
        TicketCategory category = TicketCategory.fromOrDefault(request.getCategory());
        Urgency urgency = Urgency.fromOrDefault(request.getUrgency());
        SlaPolicy policy = requirePolicy(urgency, operator.id());
        List<TicketAttachment> attachments = ticketAttachmentLinker
                .resolveAttachments(request.getImageUrls(), operator.id());

        // 基准时刻只取一次并截到秒：createdAt 与两个 deadline 必须同源，且与 DATETIME(0) 的落库精度一致，
        // 否则「deadline - createdAt」会少算 1 分钟
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime responseDeadline = now.plusMinutes(policy.getResponseMinutes());
        LocalDateTime resolveDeadline = now.plusMinutes(policy.getResolveMinutes());

        Ticket ticket = buildTicket(request, customer, device, category, urgency, policy,
                operator.id(), now, responseDeadline, resolveDeadline);
        insertWithUniqueTicketNo(ticket);
        // 建单事件先于附件事件：同一秒内先有 CREATE，再按挂载顺序追加 ATTACH
        ticketEventMapper.insert(buildCreateEvent(ticket.getId(), operator.id(), now));
        // 回填与插入同事务：附件被并发抢占时抛 409，连工单与事件一起回滚
        ticketAttachmentLinker.linkAttachments(attachments, ticket.getId(), operator.id());
        ticketAttachEventWriter.writeUploads(attachments, operator.id(), now);

        // 重新查一次拿数据库生成的 version / updated_at，避免响应里出现 null
        Ticket saved = ticketMapper.selectById(ticket.getId());
        log.info("建单成功, ticketId={}, ticketNo={}, category={}, urgency={}, reporterId={}, attachments={}",
                saved.getId(), saved.getTicketNo(), category, urgency, operator.id(), attachments.size());
        return ticketAssembler.assembleDetail(saved, now);
    }

    /**
     * 分页查询工单。
     *
     * <p>
     * 事务边界：只读，不加事务。数据范围必须先下推成 SQL 条件再分页，否则
     * {@code total} 会把不可见行算进去；名称字段按当前页批量回填，避免 N+1。
     * </p>
     *
     * @param query 查询条件，分页参数已带默认值
     * @return 分页结果，按创建时间倒序
     * @throws BusinessException 状态/紧急度/分类取值非法时抛出 400
     */
    @Override
    public PageResult<TicketListItemVO> page(TicketQueryRequest query) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        LambdaQueryWrapper<Ticket> wrapper = buildListWrapper(query, scope, operator.id());
        IPage<Ticket> page = ticketMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        return ticketAssembler.assemblePage(page, LocalDateTime.now(clock).withNano(0));
    }

    /**
     * 查询工单详情。
     *
     * <p>
     * 事务边界：只读，不加事务。数据范围判定与列表共用 {@code TicketScope}，
     * 越权与不存在统一 404，避免通过响应差异枚举他人工单。
     * </p>
     *
     * @param id 工单 ID
     * @return 工单详情，含客户/设备、SLA、AI 摘要位与附件
     * @throws BusinessException 工单不存在或不在当前用户数据范围内时抛出 404
     */
    @Override
    public TicketDetailVO detail(Long id) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        Ticket ticket = ticketAccessGuard.requireVisible(id, scope, operator);
        return ticketAssembler.assembleDetail(ticket, LocalDateTime.now(clock).withNano(0));
    }

    /**
     * 编辑工单的标题、描述、分类、紧急度与设备。
     *
     * <p>
     * 事务边界：主表更新与紧急度触发的 SLA 重算在同一事务内，任一步失败整体回滚。
     * 幂等性：字段未变化时不写库，{@code version} 与 {@code updated_at} 都不变；
     * 有变更时以 {@code version} 做乐观锁，重复提交旧版本返回 409。
     * </p>
     *
     * @param id      工单 ID
     * @param request 编辑入参，五个业务字段均可选，不传表示保留原值
     * @return 编辑后的工单详情
     * @throws BusinessException 越权或不存在抛 404；终态、报修人非 NEW、版本冲突抛 409；
     *                           取值非法或设备不属于该客户抛 400；新紧急度无启用策略抛 503
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO update(Long id, TicketUpdateRequest request) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        Ticket ticket = ticketAccessGuard.requireVisible(id, scope, operator);
        ticketAccessGuard.assertEditable(ticket, operator);
        ticketAccessGuard.assertVersionMatches(ticket, request.getVersion(), operator);

        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        EditChanges changes = applyChanges(ticket, request, operator.id());
        if (!changes.dirty()) {
            // 空更新不写库：既不校验 version，也不刷新 updated_at，保证重放结果一致
            log.debug("编辑工单无字段变更，直接返回详情, ticketId={}, operatorId={}", id, operator.id());
            return ticketAssembler.assembleDetail(ticket, now);
        }
        if (changes.urgencyChanged()) {
            SlaPolicy policy = requirePolicy(Urgency.from(ticket.getUrgency()), operator.id());
            rebaseSla(ticket, policy, now);
        }
        if (ticketMapper.updateById(ticket) == 0) {
            log.warn("编辑工单版本冲突, ticketId={}, operatorId={}, requestVersion={}",
                    id, operator.id(), request.getVersion());
            throw new BusinessException(409, TicketAccessGuard.MSG_VERSION_CONFLICT);
        }
        // 有变更才写事件：空更新在上一分支已返回，时间线不应出现「什么都没改」的记录
        ticketEventMapper.insert(buildUpdateEvent(ticket.getId(), changes.fields(), operator.id(), now));
        log.info("编辑工单成功, ticketId={}, operatorId={}, fields={}, urgencyChanged={}, version={}",
                id, operator.id(), changes.fields(), changes.urgencyChanged(), ticket.getVersion());
        return ticketAssembler.assembleDetail(ticketMapper.selectById(id), now);
    }

    /**
     * 取消工单。
     *
     * <p>
     * 顺序固定：可见性 404 → 版本 409 → 状态 409。状态更新与时间线写入同事务，
     * 事件内容固定为请求里的原因，时间线因此能回答「谁、什么时候、为什么取消」。
     * </p>
     *
     * @param id      工单 ID
     * @param request 取消入参
     * @return 取消后的工单详情
     * @throws BusinessException 不存在或越权抛 404；版本冲突或状态不可取消抛 409
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO cancel(Long id, TicketCancelRequest request) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        Ticket ticket = ticketAccessGuard.requireVisible(id, scope, operator);
        ticketAccessGuard.assertVersionMatches(ticket, request.getVersion(), operator);

        TicketStatus status = TicketStatus.from(ticket.getStatus());
        // RESOLVED 等待验收不可取消；三个终态同样没有出边，所以不能只判终态
        if (status == TicketStatus.RESOLVED
                || status == TicketStatus.CLOSED
                || status == TicketStatus.REJECTED
                || status == TicketStatus.CANCELLED) {
            log.warn("取消被拒绝：当前状态不可取消, ticketId={}, status={}, operatorId={}",
                    id, status, operator.id());
            throw new BusinessException(409, "当前状态 " + status.name() + " 不可取消");
        }

        String reason = request.getReason().trim();
        String fromStatus = ticket.getStatus();
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);

        ticket.setStatus(TicketStatus.CANCELLED.name());
        if (ticketMapper.updateById(ticket) == 0) {
            log.warn("取消工单版本冲突, ticketId={}, operatorId={}, requestVersion={}",
                    id, operator.id(), request.getVersion());
            throw new BusinessException(409, TicketAccessGuard.MSG_VERSION_CONFLICT);
        }
        ticketEventMapper.insert(buildCancelEvent(ticket.getId(), fromStatus, reason, operator.id(), now));

        // 只记录原因长度：取消原因是用户自由输入，原文进日志既冗长也可能带敏感信息
        log.info("取消工单成功, ticketId={}, operatorId={}, fromStatus={}, reasonLength={}",
                id, operator.id(), fromStatus, reason.length());
        return ticketAssembler.assembleDetail(ticketMapper.selectById(id), now);
    }

    /**
     * 评价已关闭的工单。
     *
     * <p>
     * 先查重复再插入，是为了给正常请求一个明确的 409；并发下的最终保证是
     * {@code ticket_feedback.uk_ticket} 唯一索引，撞键同样转 409。
     * </p>
     *
     * @param id      工单 ID
     * @param request 评价入参
     * @return 新建的评价
     * @throws BusinessException 不存在或越权抛 404；未关闭或重复评价抛 409
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketFeedbackVO feedback(Long id, TicketFeedbackRequest request) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        Ticket ticket = ticketAccessGuard.requireVisible(id, scope, operator);
        assertFeedbackAllowed(ticket, operator);

        if (ticketFeedbackMapper.exists(Wrappers.<TicketFeedback>lambdaQuery()
                .eq(TicketFeedback::getTicketId, id))) {
            log.warn("评价被拒绝：该工单已评价, ticketId={}, operatorId={}", id, operator.id());
            throw new BusinessException(409, "该工单已评价");
        }

        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        TicketFeedback feedback = new TicketFeedback();
        feedback.setTicketId(id);
        feedback.setScore(request.getScore());
        feedback.setComment(StringUtils.hasText(request.getComment()) ? request.getComment().trim() : null);
        feedback.setCreatedAt(now);
        try {
            ticketFeedbackMapper.insert(feedback);
        } catch (DuplicateKeyException e) {
            log.warn("评价并发重复提交, ticketId={}, operatorId={}", id, operator.id());
            throw new BusinessException(409, "该工单已评价");
        }
        // 评价正文只存 ticket_feedback，事件只留分数，避免同一段文字双写后口径不一致
        ticketEventMapper.insert(buildFeedbackEvent(id, feedback.getScore(), operator.id(), now));

        log.info("工单评价成功, ticketId={}, feedbackId={}, score={}, operatorId={}",
                id, feedback.getId(), feedback.getScore(), operator.id());
        return toFeedbackVO(feedback);
    }

    /**
     * 派单：把 NEW 工单指派给本团队工程师。
     *
     * <p>
     * 判定顺序固定：可见性 404 → 版本 409 → 状态 409 → 工程师 400。
     * 先判 404 再判 409，避免通过状态码差异确认「工单存在」；
     * 版本放在状态之前，与取消、编辑保持一致的口径。
     * </p>
     *
     * <p>
     * 待派单池例外：{@code NEW} 且 {@code team_id} 为空的工单尚未归属任何团队，
     * 按数据范围主管本应看不到它，但派单正是把它归入团队的动作，因此允许主管派这类单；
     * 派单成功后 {@code team_id} 随工程师写入，工单随即进入该主管的可见范围。
     * </p>
     *
     * @param id      工单 ID
     * @param request 派单入参
     * @return 派单后的工单详情
     * @throws BusinessException 不存在或越权抛 404；非主管/管理员抛 403；
     *                           状态不可派单或版本冲突抛 409；工程师不可派抛 400
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO assign(Long id, TicketAssignRequest request) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        Ticket ticket = requireAssignableTicket(id, scope, operator);
        ticketAccessGuard.assertVersionMatches(ticket, request.getVersion(), operator);

        TicketStatus status = TicketStatus.from(ticket.getStatus());
        if (status != TicketStatus.NEW) {
            log.warn("派单被拒绝：当前状态不可派单, ticketId={}, status={}, operatorId={}",
                    id, status, operator.id());
            throw new BusinessException(409, "当前状态 " + status.name() + " 不可派单");
        }

        SysUser assignee = requireAssignableEngineer(request.getAssigneeId(), scope, operator);

        String fromStatus = ticket.getStatus();
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        ticket.setAssigneeId(assignee.getId());
        // 团队随工程师走：不允许出现「工程师在 A 组、工单挂在 B 组」
        ticket.setTeamId(assignee.getTeamId());
        ticket.setStatus(TicketStatus.ASSIGNED.name());
        if (ticketMapper.updateById(ticket) == 0) {
            log.warn("派单版本冲突, ticketId={}, operatorId={}, requestVersion={}",
                    id, operator.id(), request.getVersion());
            throw new BusinessException(409, TicketAccessGuard.MSG_VERSION_CONFLICT);
        }

        String comment = trimToNull(request.getComment());
        ticketEventMapper.insert(buildAssignEvent(ticket.getId(), fromStatus, assignee, comment,
                operator.id(), now));

        // 备注原文不进日志：可能带客户现场细节，只记录长度
        log.info("派单成功, ticketId={}, assigneeId={}, teamId={}, fromStatus={}, operatorId={}, commentLength={}",
                id, assignee.getId(), assignee.getTeamId(), fromStatus, operator.id(),
                comment == null ? 0 : comment.length());
        return ticketAssembler.assembleDetail(ticketMapper.selectById(id), now);
    }

    /**
     * 校验工单是否允许评价。
     *
     * <p>
     * 只判状态：接口层已用 {@code @RequiredRole(REPORTER)} 排除其它角色，
     * 而报修人的数据范围只放行本人的工单，非本人在上一步已经 404。
     * 这里再判一次「是否本人」只会把 404 变成 409。
     * </p>
     *
     * @param ticket   工单实体
     * @param operator 当前登录用户，仅用于日志
     * @throws BusinessException 工单未关闭时抛出 409
     */
    private void assertFeedbackAllowed(Ticket ticket, LoginUser operator) {
        TicketStatus status = TicketStatus.from(ticket.getStatus());
        if (status != TicketStatus.CLOSED) {
            log.warn("评价被拒绝：工单未关闭, ticketId={}, status={}, operatorId={}",
                    ticket.getId(), status, operator.id());
            throw new BusinessException(409, "工单未关闭，不能评价");
        }
    }

    /**
     * 构造取消事件，用于时间线。
     *
     * @param ticketId   工单 ID
     * @param fromStatus 取消前状态
     * @param reason     取消原因
     * @param operatorId 操作人 ID
     * @param now        发生时间
     * @return 待插入的事件实体
     */
    private TicketEvent buildCancelEvent(Long ticketId, String fromStatus, String reason,
            Long operatorId, LocalDateTime now) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setType(TicketEventType.CANCEL.name());
        event.setFromStatus(fromStatus);
        event.setToStatus(TicketStatus.CANCELLED.name());
        event.setOperatorId(operatorId);
        event.setContent(reason);
        event.setCreatedAt(now);
        return event;
    }

    /**
     * 派单场景的工单可见性判定。
     *
     * <p>
     * 与详情、编辑不同，派单要在「工单归属团队」尚未确定时可用：
     * {@code NEW} 且 {@code team_id} 为空的待派单池放行给所有主管，
     * 否则主管永远看不到需要他派单的工单（详见开发文档 3.3.1 的过渡口径）。
     * 已归属团队的单仍按 {@link TicketScope} 判定，跨团队统一 404，
     * 不区分「不存在」与「无权派单」。
     * </p>
     *
     * <p>
     * 角色在这里只参与「范围」计算：{@code ADMIN} 全量、{@code SUPERVISOR} 可认领待派单池。
     * 「只有主管/管理员能调这个接口」由接口层的 {@code @RequiredRole} 负责，
     * 本方法不重复返回 403，避免同一规则在两处实现。
     * </p>
     *
     * @param id       工单 ID
     * @param scope    当前用户数据范围
     * @param operator 当前登录用户，用于日志与角色判定
     * @return 工单实体
     * @throws BusinessException 工单不存在或不在可派单范围内时抛出 404
     */
    private Ticket requireAssignableTicket(Long id, TicketScope scope, LoginUser operator) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            log.warn("派单被拒绝：工单不存在, ticketId={}, operatorId={}", id, operator.id());
            throw new BusinessException(404, TicketAccessGuard.MSG_NOT_FOUND);
        }

        Role role = Role.from(operator.role());
        if (role == Role.ADMIN) {
            return ticket;
        }

        // 只有主管能认领待派单池；这里判角色是为了算「范围」，不是重复接口层的 403——
        // 工程师或报修人即使绕过 HTTP 直接调用，也只会落到下面的 scope 判定得到 404
        boolean pendingPool = role == Role.SUPERVISOR
                && ticket.getTeamId() == null
                && TicketStatus.NEW.name().equals(ticket.getStatus());
        if (pendingPool
                || scope.allows(ticket.getReporterId(), ticket.getAssigneeId(), ticket.getTeamId())) {
            return ticket;
        }

        log.warn("派单越权被拒绝, ticketId={}, teamId={}, operatorId={}",
                id, ticket.getTeamId(), operator.id());
        throw new BusinessException(404, TicketAccessGuard.MSG_NOT_FOUND);
    }

    /**
     * 校验被派工程师是否可接单。
     *
     * <p>
     * 四道门槛：存在且启用、角色为 {@code ENGINEER}、已加入团队、属于主管管理的团队。
     * 全部失败都返回 400 而不是 404：这里校验的是请求参数指向的人是否可用，
     * 不是工单资源本身；且派单是内部管理动作，不存在社工枚举风险。
     * 管理员不校验团队，可以派给任意团队（开发文档 3.3.1 对 ADMIN 不限范围）。
     * </p>
     *
     * @param assigneeId 被派工程师 ID
     * @param scope      当前用户数据范围
     * @param operator   当前登录用户，用于日志与角色判定
     * @return 被派工程师实体
     * @throws BusinessException 不存在、停用、非工程师、未加入团队或跨团队时抛出 400
     */
    private SysUser requireAssignableEngineer(Long assigneeId, TicketScope scope, LoginUser operator) {
        SysUser assignee = sysUserMapper.selectById(assigneeId);
        if (assignee == null || !Integer.valueOf(1).equals(assignee.getStatus())) {
            log.warn("派单被拒绝：工程师不存在或已停用, assigneeId={}, operatorId={}",
                    assigneeId, operator.id());
            throw new BusinessException(400, "工程师不存在或已停用");
        }
        if (!Role.ENGINEER.name().equals(assignee.getRole())) {
            log.warn("派单被拒绝：目标账号不是工程师, assigneeId={}, role={}, operatorId={}",
                    assigneeId, assignee.getRole(), operator.id());
            throw new BusinessException(400, "只能派给工程师");
        }
        if (assignee.getTeamId() == null) {
            log.warn("派单被拒绝：工程师未加入团队, assigneeId={}, operatorId={}",
                    assigneeId, operator.id());
            throw new BusinessException(400, "该工程师未加入团队，无法派单");
        }
        if (Role.from(operator.role()) == Role.SUPERVISOR
                && !scope.visibility().teamIds().contains(assignee.getTeamId())) {
            log.warn("派单被拒绝：工程师不属于本团队, assigneeId={}, assigneeTeamId={}, operatorId={}",
                    assigneeId, assignee.getTeamId(), operator.id());
            throw new BusinessException(400, "只能派给本团队工程师");
        }
        return assignee;
    }

    /**
     * 构造派单事件，用于时间线。
     *
     * <p>
     * 正文写入「指派工程师 + 备注」而不是只存备注：时间线不带关联查询，
     * 只存备注会让读者看不出这一单派给了谁。
     * </p>
     *
     * @param ticketId   工单 ID
     * @param fromStatus 派单前状态
     * @param assignee   被派工程师
     * @param comment    派单备注，可为 null
     * @param operatorId 操作人 ID
     * @param now        发生时间
     * @return 待插入的事件实体
     */
    private TicketEvent buildAssignEvent(Long ticketId, String fromStatus, SysUser assignee,
            String comment, Long operatorId, LocalDateTime now) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setType(TicketEventType.ASSIGN.name());
        event.setFromStatus(fromStatus);
        event.setToStatus(TicketStatus.ASSIGNED.name());
        event.setOperatorId(operatorId);
        String content = "指派工程师：" + assignee.getDisplayName();
        if (comment != null) {
            content = content + "；备注：" + comment;
        }
        event.setContent(content);
        event.setCreatedAt(now);
        return event;
    }

    /**
     * 构造建单事件，用于时间线。
     *
     * <p>
     * 从「无」到 {@code NEW}，所以只有 {@code toStatus}，没有 {@code fromStatus}；
     * 正文留空，工单标题与描述已经在工单详情里。
     * </p>
     *
     * @param ticketId   工单 ID
     * @param operatorId 报修人 ID
     * @param now        建单时刻
     * @return 待插入的事件实体
     */
    private TicketEvent buildCreateEvent(Long ticketId, Long operatorId, LocalDateTime now) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setType(TicketEventType.CREATE.name());
        event.setToStatus(TicketStatus.NEW.name());
        event.setOperatorId(operatorId);
        event.setCreatedAt(now);
        return event;
    }

    /**
     * 构造评价事件，用于时间线。
     *
     * <p>
     * 只把分数写进 {@code metadata.score}，评价正文仍只存 {@code ticket_feedback}：
     * 同一段文字双写后，修改或统计口径一旦不一致就无法判断哪份为准。
     * </p>
     *
     * @param ticketId   工单 ID
     * @param score      评分，1-5
     * @param operatorId 报修人 ID
     * @param now        评价时刻
     * @return 待插入的事件实体
     */
    private TicketEvent buildFeedbackEvent(Long ticketId, Integer score, Long operatorId,
            LocalDateTime now) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setType(TicketEventType.FEEDBACK.name());
        event.setOperatorId(operatorId);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("score", score);
        event.setMetadata(metadata.toString());
        event.setCreatedAt(now);
        return event;
    }

    /**
     * 构造编辑事件，用于时间线。
     *
     * <p>
     * 只记录实际变更的字段名，不记录旧值与新值：旧描述可能包含客户现场细节，
     * 新值在工单详情里能直接读到，事件只回答「谁在什么时候改了哪几个字段」。
     * 字段名与接口入参保持一致，前端可直接据此渲染。
     * </p>
     *
     * @param ticketId   工单 ID
     * @param fields     实际变更的字段名，至少一个
     * @param operatorId 操作人 ID
     * @param now        发生时间
     * @return 待插入的事件实体
     */
    private TicketEvent buildUpdateEvent(Long ticketId, List<String> fields, Long operatorId,
            LocalDateTime now) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setType(TicketEventType.UPDATE.name());
        event.setOperatorId(operatorId);
        // 正文给人读，metadata 给前端做结构化展示；两者都不含旧值
        event.setContent("修改字段：" + String.join("、", fields));
        ObjectNode metadata = objectMapper.createObjectNode();
        ArrayNode fieldsNode = metadata.putArray("fields");
        fields.forEach(fieldsNode::add);
        event.setMetadata(metadata.toString());
        event.setCreatedAt(now);
        return event;
    }

    /**
     * 评价实体转视图。
     *
     * @param feedback 评价实体
     * @return 评价视图
     */
    private TicketFeedbackVO toFeedbackVO(TicketFeedback feedback) {
        return TicketFeedbackVO.builder()
                .id(feedback.getId())
                .ticketId(feedback.getTicketId())
                .score(feedback.getScore())
                .comment(feedback.getComment())
                .createdAt(feedback.getCreatedAt())
                .build();
    }

    /**
     * 拼装列表查询条件：数据范围 + 业务筛选 + 排序。
     *
     * @param query  查询条件
     * @param scope  当前用户的数据范围
     * @param userId 当前用户 ID，供「我相关」使用
     * @return 查询包装器
     * @throws BusinessException 状态/紧急度/分类取值非法时抛出 400
     */
    private LambdaQueryWrapper<Ticket> buildListWrapper(TicketQueryRequest query, TicketScope scope, Long userId) {
        LambdaQueryWrapper<Ticket> wrapper = Wrappers.<Ticket>lambdaQuery();
        applyScope(wrapper, scope);

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            // 关键字是「或」关系，必须整体括起来，否则会把数据范围条件一起或掉
            wrapper.and(w -> w.like(Ticket::getTitle, keyword).or().like(Ticket::getTicketNo, keyword));
        }
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(Ticket::getStatus, TicketStatus.from(query.getStatus()).name());
        }
        if (StringUtils.hasText(query.getUrgency())) {
            wrapper.eq(Ticket::getUrgency, Urgency.from(query.getUrgency()).name());
        }
        if (StringUtils.hasText(query.getCategory())) {
            wrapper.eq(Ticket::getCategory, TicketCategory.from(query.getCategory()).name());
        }
        if (query.getAssigneeId() != null) {
            wrapper.eq(Ticket::getAssigneeId, query.getAssigneeId());
        }
        if (query.getTeamId() != null) {
            wrapper.eq(Ticket::getTeamId, query.getTeamId());
        }
        if (Boolean.TRUE.equals(query.getMine())) {
            wrapper.and(w -> w.eq(Ticket::getReporterId, userId).or().eq(Ticket::getAssigneeId, userId));
        }
        if (Boolean.TRUE.equals(query.getEscalated())) {
            wrapper.and(w -> w.isNotNull(Ticket::getResponseEscalatedAt)
                    .or().isNotNull(Ticket::getResolveEscalatedAt));
        }
        // 创建时间可能相同，追加 id 倒序保证分页顺序稳定
        wrapper.orderByDesc(Ticket::getCreatedAt).orderByDesc(Ticket::getId);
        return wrapper;
    }

    /**
     * 把数据范围下推为查询条件。
     *
     * <p>
     * 主管的团队集合为空时必须拼恒假条件：跳过条件会让主管看到全量工单，
     * 用 {@code IN ()} 则是非法 SQL，因此在没有团队时显式写 {@code 1 = 0}。
     * </p>
     *
     * @param wrapper 查询包装器
     * @param scope   数据范围
     */
    private void applyScope(LambdaQueryWrapper<Ticket> wrapper, TicketScope scope) {
        TicketScope.Visibility visibility = scope.visibility();
        if (visibility.unrestricted()) {
            return;
        }
        if (visibility.reporterId() != null) {
            wrapper.eq(Ticket::getReporterId, visibility.reporterId());
            return;
        }
        if (visibility.assigneeId() != null) {
            wrapper.eq(Ticket::getAssigneeId, visibility.assigneeId());
            return;
        }
        if (visibility.teamIds().isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.in(Ticket::getTeamId, visibility.teamIds());
    }

    /**
     * 去掉首尾空白，空串归一为 null。
     *
     * @param value 原始字符串
     * @return 去空白后的值，空白时返回 null
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 组装待插入的工单实体。
     *
     * @param request          建单入参
     * @param customer         客户
     * @param device           设备，可为 null
     * @param category         分类
     * @param urgency          紧急度
     * @param policy           命中的 SLA 策略
     * @param reporterId       报修人 ID
     * @param now              建单时刻
     * @param responseDeadline 响应截止
     * @param resolveDeadline  解决截止
     * @return 工单实体，工单号留待插入前生成
     */
    private Ticket buildTicket(TicketCreateRequest request, Customer customer, Device device,
            TicketCategory category, Urgency urgency, SlaPolicy policy, Long reporterId,
            LocalDateTime now, LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
        Ticket ticket = new Ticket();
        ticket.setTitle(request.getTitle().trim());
        ticket.setDescription(request.getDescription().trim());
        ticket.setCategory(category.name());
        ticket.setUrgency(urgency.name());
        ticket.setStatus(TicketStatus.NEW.name());
        ticket.setCustomerId(customer.getId());
        ticket.setDeviceId(device == null ? null : device.getId());
        ticket.setReporterId(reporterId);
        ticket.setSlaPolicyId(policy.getId());
        ticket.setResponseDeadline(responseDeadline);
        ticket.setResolveDeadline(resolveDeadline);
        ticket.setAiCorrected(0);
        ticket.setCreatedAt(now);
        return ticket;
    }

    /**
     * 生成工单号并插入；唯一索引冲突时换号重试一次。
     *
     * <p>
     * 冲突只可能来自降级路径的并发撞号（Redis 正常时序列自增不会重复）。
     * 重试仍失败说明降级路径持续撞号，返回 503 让调用方重试。
     * </p>
     *
     * @param ticket 待插入工单
     * @throws BusinessException 连续两次冲突时抛出 503
     */
    private void insertWithUniqueTicketNo(Ticket ticket) {
        for (int attempt = 1; attempt <= MAX_INSERT_ATTEMPTS; attempt++) {
            // 上一次 insert 失败时自增主键可能已被回填到实体上；不清空就换号重试会带着旧 id 再插一次，
            // 报主键冲突而不是工单号冲突，把真实的失败原因掩盖掉
            ticket.setId(null);
            ticket.setTicketNo(ticketNoGenerator.next());
            try {
                ticketMapper.insert(ticket);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == MAX_INSERT_ATTEMPTS) {
                    log.error("工单号连续冲突，建单失败, ticketNo={}", ticket.getTicketNo(), e);
                    throw new BusinessException(503, "工单号生成冲突，请稍后重试");
                }
                log.warn("工单号唯一冲突，重新取号, ticketNo={}", ticket.getTicketNo());
            }
        }
    }

    /**
     * 校验客户存在。
     *
     * @param customerId 客户 ID
     * @param operatorId 当前用户 ID，仅用于日志
     * @return 客户实体
     * @throws BusinessException 客户不存在时抛出 400
     */
    private Customer requireCustomer(Long customerId, Long operatorId) {
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            log.warn("建单被拒绝：客户不存在, customerId={}, operatorId={}", customerId, operatorId);
            throw new BusinessException(400, "客户不存在");
        }
        return customer;
    }

    /**
     * 校验设备存在且属于该客户。
     *
     * @param deviceId   设备 ID，可为 null
     * @param customerId 客户 ID
     * @param operatorId 当前用户 ID，仅用于日志
     * @return 设备实体，deviceId 为 null 时返回 null
     * @throws BusinessException 设备不存在或不属于该客户时抛出 400
     */
    private Device requireDeviceOfCustomer(Long deviceId, Long customerId, Long operatorId) {
        if (deviceId == null) {
            return null;
        }
        Device device = deviceMapper.selectById(deviceId);
        if (device == null || !Objects.equals(device.getCustomerId(), customerId)) {
            log.warn("设备校验未通过：不存在或不属于该客户, deviceId={}, customerId={}, operatorId={}",
                    deviceId, customerId, operatorId);
            throw new BusinessException(400, "设备不存在或不属于该客户");
        }
        return device;
    }

    /**
     * 按紧急度匹配启用中的 SLA 策略。
     *
     * @param urgency    紧急度
     * @param operatorId 当前用户 ID，仅用于日志
     * @return SLA 策略
     * @throws BusinessException 没有启用策略时抛出 503
     */
    private SlaPolicy requirePolicy(Urgency urgency, Long operatorId) {
        SlaPolicy policy = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
                .eq(SlaPolicy::getPriority, urgency.name())
                .eq(SlaPolicy::getEnabled, 1));
        if (policy == null) {
            log.error("紧急度无启用的 SLA 策略, urgency={}, operatorId={}", urgency, operatorId);
            throw new BusinessException(503, "SLA 策略未配置，请联系管理员");
        }
        return policy;
    }

    /**
     * 把请求里的可选字段合并到工单实体上。
     *
     * <p>
     * 不传或传 null 表示保留原值；传了但与库里相同也不算变更 —— 否则同一个请求重放两次
     * 会让 version 无谓自增，空更新就不幂等了。取值校验（空白、字典外、设备归属）都在这里完成，
     * 校验通过才写字段，避免改到一半才发现非法值。
     * </p>
     *
     * @param ticket     待编辑工单
     * @param request    编辑入参
     * @param operatorId 当前用户 ID，仅用于日志
     * @return 是否真的发生变更、紧急度是否变更
     * @throws BusinessException 标题/描述为空白、分类/紧急度不合法、设备不属于该客户时抛出 400
     */
    private EditChanges applyChanges(Ticket ticket, TicketUpdateRequest request, Long operatorId) {
        boolean dirty = false;
        boolean urgencyChanged = false;
        List<String> fields = new ArrayList<>();

        if (request.getTitle() != null) {
            String title = request.getTitle().trim();
            if (title.isEmpty()) {
                log.warn("编辑被拒绝：标题为空白, ticketId={}, operatorId={}",
                        ticket.getId(), operatorId);
                throw new BusinessException(400, "标题不能为空白");
            }
            if (!title.equals(ticket.getTitle())) {
                ticket.setTitle(title);
                dirty = true;
                fields.add("title");
            }
        }
        if (request.getDescription() != null) {
            String description = request.getDescription().trim();
            if (description.isEmpty()) {
                log.warn("编辑被拒绝：描述为空白, ticketId={}, operatorId={}",
                        ticket.getId(), operatorId);
                throw new BusinessException(400, "描述不能为空白");
            }
            if (!description.equals(ticket.getDescription())) {
                ticket.setDescription(description);
                dirty = true;
                fields.add("description");
            }
        }
        if (StringUtils.hasText(request.getCategory())) {
            String category = TicketCategory.from(request.getCategory()).name();
            if (!category.equals(ticket.getCategory())) {
                ticket.setCategory(category);
                dirty = true;
                fields.add("category");
            }
        }
        if (StringUtils.hasText(request.getUrgency())) {
            Urgency urgency = Urgency.from(request.getUrgency());
            if (!urgency.name().equals(ticket.getUrgency())) {
                ticket.setUrgency(urgency.name());
                urgencyChanged = true;
                dirty = true;
                fields.add("urgency");
            }
        }
        if (request.getDeviceId() != null) {
            Device device = requireDeviceOfCustomer(request.getDeviceId(), ticket.getCustomerId(), operatorId);
            if (!Objects.equals(device.getId(), ticket.getDeviceId())) {
                ticket.setDeviceId(device.getId());
                dirty = true;
                fields.add("deviceId");
            }
        }
        return new EditChanges(dirty, urgencyChanged, List.copyOf(fields));
    }

    /**
     * 按新紧急度重算 SLA：换策略、以本次请求时刻为基准重写两个 deadline。
     *
     * <p>
     * RC-001 后没有暂停状态，deadline 一律从本次请求时刻重新起算，不再做暂停平移。
     * </p>
     *
     * @param ticket 工单实体
     * @param policy 新紧急度命中的启用策略
     * @param now    本次请求时刻
     */
    private void rebaseSla(Ticket ticket, SlaPolicy policy, LocalDateTime now) {
        ticket.setSlaPolicyId(policy.getId());
        ticket.setResponseDeadline(now.plusMinutes(policy.getResponseMinutes()));
        ticket.setResolveDeadline(now.plusMinutes(policy.getResolveMinutes()));
        log.info("编辑紧急度重算 SLA, ticketId={}, policyId={}, responseDeadline={}, resolveDeadline={}",
                ticket.getId(), policy.getId(), ticket.getResponseDeadline(), ticket.getResolveDeadline());
    }

    /**
     * 一次编辑请求的变更结果。
     *
     * @param dirty          是否有任何字段真的发生变化
     * @param urgencyChanged 紧急度是否变化，只有变化时才重算 SLA
     * @param fields         实际变更的字段名，按请求字段命名，供 UPDATE 事件的 metadata 使用
     */
    private record EditChanges(boolean dirty, boolean urgencyChanged, List<String> fields) {
    }
}