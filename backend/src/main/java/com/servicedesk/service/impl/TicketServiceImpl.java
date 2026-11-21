package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.domain.ticket.TicketScope;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.dto.request.ticket.TicketQueryRequest;
import com.servicedesk.dto.request.ticket.TicketUpdateRequest;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.enums.TicketCategory;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.entity.enums.Urgency;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.TicketService;
import com.servicedesk.service.support.TicketAccessGuard;
import com.servicedesk.service.support.TicketAssembler;
import com.servicedesk.service.support.TicketAttachmentLinker;
import com.servicedesk.service.support.TicketNoGenerator;
import com.servicedesk.service.support.TicketScopeResolver;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketListItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 工单服务实现（建单 T-01、列表 T-02、详情 T-03、编辑 T-04）。
 *
 * <p>
 * 属于业务实现层。建单主链路：
 * </p>
 * <ol>
 * <li>校验客户、设备归属、分类/紧急度取值与附件可用性——全部在写库前完成；</li>
 * <li>按紧急度匹配启用中的 SLA 策略，用同一基准时刻算出两个 deadline；</li>
 * <li>生成工单号并插入工单（`NEW`、暂停 0 分钟、AI 字段留空）；</li>
 * <li>把已上传附件的 {@code ticket_id} 回填为本工单。</li>
 * </ol>
 *
 * <p>
 * 关键约束：工单插入与附件回填在同一个事务内，附件回填失败要连工单一起回滚，
 * 不能留下「工单没有图」的半成品；工单号来自 Redis，回滚不会退号，允许跳号。
 * </p>
 *
 * <p>
 * 详情与编辑共用 {@link TicketAssembler#assembleDetail(Ticket, LocalDateTime)}：建单、详情、编辑
 * 三个出口返回同一个 A.8 结构，任何一处新增字段都只改装配器。读路径的时间基准统一由调用方传入，
 * 暂停中改用 {@code paused_at}，保证列表与详情的倒计时口径一致。
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

    /** 客户 Mapper，仅用于建单前校验客户存在，装配由 {@link TicketAssembler} 负责。 */
    private final CustomerMapper customerMapper;

    /** 设备 Mapper，仅用于校验设备存在性与归属。 */
    private final DeviceMapper deviceMapper;

    /** SLA 策略 Mapper，仅用于按紧急度匹配启用策略。 */
    private final SlaPolicyMapper slaPolicyMapper;

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
        // 回填与插入同事务：附件被并发抢占时抛 409，连工单一起回滚
        ticketAttachmentLinker.linkAttachments(attachments, ticket.getId(), operator.id());

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
        log.info("编辑工单成功, ticketId={}, operatorId={}, urgencyChanged={}, version={}",
                id, operator.id(), changes.urgencyChanged(), ticket.getVersion());
        return ticketAssembler.assembleDetail(ticketMapper.selectById(id), now);
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
        ticket.setPausedTotalMinutes(0);
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
            }
        }
        if (StringUtils.hasText(request.getCategory())) {
            String category = TicketCategory.from(request.getCategory()).name();
            if (!category.equals(ticket.getCategory())) {
                ticket.setCategory(category);
                dirty = true;
            }
        }
        if (StringUtils.hasText(request.getUrgency())) {
            Urgency urgency = Urgency.from(request.getUrgency());
            if (!urgency.name().equals(ticket.getUrgency())) {
                ticket.setUrgency(urgency.name());
                urgencyChanged = true;
                dirty = true;
            }
        }
        if (request.getDeviceId() != null) {
            Device device = requireDeviceOfCustomer(request.getDeviceId(), ticket.getCustomerId(), operatorId);
            if (!Objects.equals(device.getId(), ticket.getDeviceId())) {
                ticket.setDeviceId(device.getId());
                dirty = true;
            }
        }
        return new EditChanges(dirty, urgencyChanged);
    }

    /**
     * 按新紧急度重算 SLA：换策略、以本次请求时刻为基准重写两个 deadline。
     *
     * <p>
     * 暂停中把 {@code paused_at} 平移到 now：从 now 起算等于把「now 之前的暂停」作废，
     * 若不平移，恢复时还会再补一次那段时间，造成重复补时。
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
        if (ticket.getPausedAt() != null) {
            ticket.setPausedAt(now);
        }
        log.info("编辑紧急度重算 SLA, ticketId={}, policyId={}, responseDeadline={}, resolveDeadline={}",
                ticket.getId(), policy.getId(), ticket.getResponseDeadline(), ticket.getResolveDeadline());
    }

    /**
     * 一次编辑请求的变更结果。
     *
     * @param dirty          是否有任何字段真的发生变化
     * @param urgencyChanged 紧急度是否变化，只有变化时才重算 SLA
     */
    private record EditChanges(boolean dirty, boolean urgencyChanged) {
    }
}
