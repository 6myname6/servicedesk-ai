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
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.enums.TicketCategory;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.entity.enums.Urgency;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.mapper.TicketAttachmentMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.TicketService;
import com.servicedesk.service.support.TicketNoGenerator;
import com.servicedesk.service.support.TicketScopeResolver;
import com.servicedesk.vo.AttachmentVO;
import com.servicedesk.vo.SlaStatusVO;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketListItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工单服务实现（建单 T-01 + 列表 T-02）。
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    /** 单次建单最多关联的附件数，与接口文档 2.2 一致。 */
    private static final int MAX_ATTACHMENTS = 5;

    /** 工单号唯一冲突时的最大尝试次数（含首次）。 */
    private static final int MAX_INSERT_ATTEMPTS = 2;

    /** 工单 Mapper。 */
    private final TicketMapper ticketMapper;

    /** 工单号生成器，Redis 日序列 + 库内兜底。 */
    private final TicketNoGenerator ticketNoGenerator;

    /** 客户 Mapper，用于校验客户存在性并回填客户快照。 */
    private final CustomerMapper customerMapper;

    /** 设备 Mapper，用于校验设备存在性与归属。 */
    private final DeviceMapper deviceMapper;

    /** SLA 策略 Mapper，按紧急度匹配启用策略。 */
    private final SlaPolicyMapper slaPolicyMapper;

    /** 附件 Mapper，用于校验并回填 {@code ticket_id}。 */
    private final TicketAttachmentMapper attachmentMapper;

    /** 用户 Mapper，列表回填处理人显示名。 */
    private final SysUserMapper sysUserMapper;

    /** 团队 Mapper，列表回填团队名。 */
    private final TeamMapper teamMapper;

    /** 数据范围解析器，把登录身份翻译成工单可见范围。 */
    private final TicketScopeResolver ticketScopeResolver;

    /** 业务时钟，建单时刻与剩余分钟都取它，禁止无参 {@code now()}。 */
    private final Clock clock;

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
        Customer customer = requireCustomer(request.getCustomerId());
        Device device = requireDeviceOfCustomer(request.getDeviceId(), customer.getId());
        TicketCategory category = TicketCategory.fromOrDefault(request.getCategory());
        Urgency urgency = Urgency.fromOrDefault(request.getUrgency());
        SlaPolicy policy = requirePolicy(urgency);
        List<TicketAttachment> attachments = resolveAttachments(request.getImageUrls(), operator.id());

        // 基准时刻只取一次并截到秒：createdAt 与两个 deadline 必须同源，且与 DATETIME(0) 的落库精度一致，
        // 否则「deadline - createdAt」会少算 1 分钟
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime responseDeadline = now.plusMinutes(policy.getResponseMinutes());
        LocalDateTime resolveDeadline = now.plusMinutes(policy.getResolveMinutes());

        Ticket ticket = buildTicket(request, customer, device, category, urgency, policy,
                operator.id(), now, responseDeadline, resolveDeadline);
        insertWithUniqueTicketNo(ticket);
        linkAttachments(attachments, ticket.getId());

        // 重新查一次拿数据库生成的 version / updated_at，避免响应里出现 null
        Ticket saved = ticketMapper.selectById(ticket.getId());
        log.info("建单成功, ticketId={}, ticketNo={}, category={}, urgency={}, reporterId={}, attachments={}",
                saved.getId(), saved.getTicketNo(), category, urgency, operator.id(), attachments.size());
        return toVO(saved, customer, device, operator, policy, attachments,
            now, responseDeadline, resolveDeadline);
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

        NameIndex names = loadNames(page.getRecords());
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        return PageResult.from(page, ticket -> toItemVO(ticket, names, now));
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
        if (hasText(query.getStatus())) {
            wrapper.eq(Ticket::getStatus, TicketStatus.from(query.getStatus()).name());
        }
        if (hasText(query.getUrgency())) {
            wrapper.eq(Ticket::getUrgency, Urgency.from(query.getUrgency()).name());
        }
        if (hasText(query.getCategory())) {
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
     * 按当前页批量加载名称，避免逐行查库。
     *
     * @param tickets 当前页工单
     * @return 四类名称索引
     */
    private NameIndex loadNames(List<Ticket> tickets) {
        if (tickets.isEmpty()) {
            return new NameIndex(Map.of(), Map.of(), Map.of(), Map.of());
        }
        Set<Long> customerIds = collectIds(tickets, Ticket::getCustomerId);
        Set<Long> deviceIds = collectIds(tickets, Ticket::getDeviceId);
        Set<Long> assigneeIds = collectIds(tickets, Ticket::getAssigneeId);
        Set<Long> teamIds = collectIds(tickets, Ticket::getTeamId);
        return new NameIndex(
            customerIds.isEmpty() ? Map.of()
                : toNameMap(customerMapper.selectByIds(customerIds), Customer::getId, Customer::getName),
            deviceIds.isEmpty() ? Map.of()
                : toNameMap(deviceMapper.selectByIds(deviceIds), Device::getId, Device::getModel),
            assigneeIds.isEmpty() ? Map.of()
                : toNameMap(sysUserMapper.selectByIds(assigneeIds), SysUser::getId, SysUser::getDisplayName),
            teamIds.isEmpty() ? Map.of()
                : toNameMap(teamMapper.selectByIds(teamIds), Team::getId, Team::getName));
    }

    /**
     * 收集某一列的非空 ID。
     *
     * @param tickets 当前页工单
     * @param getter  取值函数
     * @return 去重后的 ID 集合
     */
    private Set<Long> collectIds(List<Ticket> tickets, Function<Ticket, Long> getter) {
        return tickets.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * 把实体列表转成 ID → 名称的映射。
     *
     * <p>
     * 不用 {@code Collectors.toMap}：设备型号允许为 null，而 {@code toMap} 遇到 null 值会抛 NPE。
     * </p>
     *
     * @param rows       实体列表
     * @param idGetter   ID 取值函数
     * @param nameGetter 名称取值函数
     * @param <T>        实体类型
     * @return ID 到名称的映射
     */
    private <T> Map<Long, String> toNameMap(List<T> rows, Function<T, Long> idGetter,
        Function<T, String> nameGetter) {
        Map<Long, String> result = new HashMap<>();
        for (T row : rows) {
            result.put(idGetter.apply(row), nameGetter.apply(row));
        }
        return result;
    }

    /**
     * 工单实体转列表项视图。
     *
     * @param ticket 工单实体
     * @param names  名称索引
     * @param now    计算剩余分钟的时刻
     * @return 列表项视图
     */
    private TicketListItemVO toItemVO(Ticket ticket, NameIndex names, LocalDateTime now) {
        Long remaining = null;
        boolean breached = false;
        if (ticket.getResolveDeadline() != null) {
            remaining = Duration.between(now, ticket.getResolveDeadline()).toMinutes();
            breached = now.isAfter(ticket.getResolveDeadline());
        }
        return TicketListItemVO.builder()
            .id(ticket.getId())
            .ticketNo(ticket.getTicketNo())
            .title(ticket.getTitle())
            .category(ticket.getCategory())
            .urgency(ticket.getUrgency())
            .status(ticket.getStatus())
            .customerName(names.customers().get(ticket.getCustomerId()))
            .deviceModel(ticket.getDeviceId() == null ? null : names.devices().get(ticket.getDeviceId()))
            .assigneeName(ticket.getAssigneeId() == null ? null : names.users().get(ticket.getAssigneeId()))
            .teamName(ticket.getTeamId() == null ? null : names.teams().get(ticket.getTeamId()))
            .slaRemainingMinutes(remaining)
            .slaBreached(breached)
            .createdAt(ticket.getCreatedAt())
            .build();
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
     * 判断字符串非空白。
     *
     * @param value 待判断字符串
     * @return true 表示存在非空白内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 当前页涉及的四类名称索引。
     *
     * @param customers 客户 ID → 客户名
     * @param devices   设备 ID → 型号
     * @param users     用户 ID → 显示名
     * @param teams     团队 ID → 团队名
     */
    private record NameIndex(Map<Long, String> customers, Map<Long, String> devices,
        Map<Long, String> users, Map<Long, String> teams) {
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
     * 把附件的 {@code ticket_id} 回填为本工单。
     *
     * @param attachments 已通过校验的附件
     * @param ticketId    新工单 ID
     */
    private void linkAttachments(List<TicketAttachment> attachments, Long ticketId) {
        if (attachments.isEmpty()) {
            return;
        }
        List<Long> attachmentIds = attachments.stream().map(TicketAttachment::getId).toList();
        attachmentMapper.update(null, Wrappers.<TicketAttachment>lambdaUpdate()
                .in(TicketAttachment::getId, attachmentIds)
                .set(TicketAttachment::getTicketId, ticketId));
        // 同步内存对象，响应里的 attachments 不必再查一次库
        attachments.forEach(attachment -> attachment.setTicketId(ticketId));
        log.info("建单关联附件成功, ticketId={}, attachmentIds={}", ticketId, attachmentIds);
    }

    /**
     * 校验客户存在。
     *
     * @param customerId 客户 ID
     * @return 客户实体
     * @throws BusinessException 客户不存在时抛出 400
     */
    private Customer requireCustomer(Long customerId) {
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            log.warn("建单被拒绝：客户不存在, customerId={}, operatorId={}",
                    customerId, UserContext.getUserId());
            throw new BusinessException(400, "客户不存在");
        }
        return customer;
    }

    /**
     * 校验设备存在且属于该客户。
     *
     * @param deviceId   设备 ID，可为 null
     * @param customerId 客户 ID
     * @return 设备实体，deviceId 为 null 时返回 null
     * @throws BusinessException 设备不存在或不属于该客户时抛出 400
     */
    private Device requireDeviceOfCustomer(Long deviceId, Long customerId) {
        if (deviceId == null) {
            return null;
        }
        Device device = deviceMapper.selectById(deviceId);
        if (device == null || !Objects.equals(device.getCustomerId(), customerId)) {
            log.warn("建单被拒绝：设备不存在或不属于该客户, deviceId={}, customerId={}, operatorId={}",
                    deviceId, customerId, UserContext.getUserId());
            throw new BusinessException(400, "设备不存在或不属于该客户");
        }
        return device;
    }

    /**
     * 按紧急度匹配启用中的 SLA 策略。
     *
     * @param urgency 紧急度
     * @return SLA 策略
     * @throws BusinessException 没有启用策略时抛出 503
     */
    private SlaPolicy requirePolicy(Urgency urgency) {
        SlaPolicy policy = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
                .eq(SlaPolicy::getPriority, urgency.name())
                .eq(SlaPolicy::getEnabled, 1));
        if (policy == null) {
            log.error("建单失败：紧急度无启用的 SLA 策略, urgency={}, operatorId={}",
                    urgency, UserContext.getUserId());
            throw new BusinessException(503, "SLA 策略未配置，请联系管理员");
        }
        return policy;
    }

    /**
     * 校验并加载待关联的附件。
     *
     * <p>
     * 只接受「当前账号上传且尚未关联工单」的附件：否则任何登录用户都能把别人的图
     * 挂到自己的工单上，或者把一张图同时挂到多张工单上。
     * </p>
     *
     * @param imageUrls  请求里的附件 URL，可为 null
     * @param reporterId 当前用户 ID
     * @return 命中的附件，按入参顺序去重后的数量与结果一致；无附件时返回空列表
     * @throws BusinessException URL 不可用或超过 5 个时抛出 400
     */
    private List<TicketAttachment> resolveAttachments(List<String> imageUrls, Long reporterId) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        List<String> urls = imageUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .distinct()
                .toList();
        if (urls.isEmpty()) {
            return List.of();
        }
        if (urls.size() > MAX_ATTACHMENTS) {
            log.warn("建单被拒绝：附件超过上限, size={}, operatorId={}", urls.size(), reporterId);
            throw new BusinessException(400, "最多关联 5 个附件");
        }

        List<TicketAttachment> attachments = attachmentMapper.selectList(
                Wrappers.<TicketAttachment>lambdaQuery()
                        .in(TicketAttachment::getFileUrl, urls)
                        .eq(TicketAttachment::getUploaderId, reporterId)
                        .isNull(TicketAttachment::getTicketId));
        if (attachments.size() != urls.size()) {
            log.warn("建单被拒绝：附件不存在、非本人上传或已被关联, requested={}, matched={}, operatorId={}",
                    urls.size(), attachments.size(), reporterId);
            throw new BusinessException(400, "附件不存在或不可用");
        }
        return attachments;
    }

    /**
     * 实体转工单详情视图。
     *
     * @param ticket           已落库工单
     * @param customer         客户
     * @param device           设备，可为 null
     * @param operator         当前用户
     * @param policy           命中的 SLA 策略
     * @param attachments      已关联附件
     * @param now              建单时刻，用于计算剩余分钟
     * @param responseDeadline 响应截止
     * @param resolveDeadline  解决截止
     * @return 工单详情视图
     */
    private TicketDetailVO toVO(Ticket ticket, Customer customer, Device device, LoginUser operator,
            SlaPolicy policy, List<TicketAttachment> attachments, LocalDateTime now,
            LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
        return TicketDetailVO.builder()
                .id(ticket.getId())
                .ticketNo(ticket.getTicketNo())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .category(ticket.getCategory())
                .urgency(ticket.getUrgency())
                .status(ticket.getStatus())
                .customer(new TicketDetailVO.CustomerBrief(customer.getId(), customer.getName(), customer.getPhone()))
                .device(device == null ? null
                        : new TicketDetailVO.DeviceBrief(
                                device.getId(), device.getModel(), device.getSerialNo(), device.getWarrantyEnd()))
                .reporter(new TicketDetailVO.UserBrief(operator.id(), operator.displayName()))
                .assignee(null)
                .team(null)
                .sla(buildSla(policy, now, responseDeadline, resolveDeadline))
                .ai(null)
                .attachments(attachments.stream().map(this::toAttachmentVO).toList())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .version(ticket.getVersion() == null ? 0 : ticket.getVersion())
                .build();
    }

    /**
     * 组装 SLA 状态：未派单工单没有首次响应，也不处于暂停。
     *
     * @param policy           命中的策略
     * @param now              当前时刻
     * @param responseDeadline 响应截止
     * @param resolveDeadline  解决截止
     * @return SLA 状态视图
     */
    private SlaStatusVO buildSla(SlaPolicy policy,
            LocalDateTime now, LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
        long responseRemaining = Duration.between(now, responseDeadline).toMinutes();
        long resolveRemaining = Duration.between(now, resolveDeadline).toMinutes();
        return SlaStatusVO.builder()
                .policyId(policy.getId())
                .policyName(policy.getName())
                .responseDeadline(responseDeadline)
                .resolveDeadline(resolveDeadline)
                .firstResponseAt(null)
                .paused(false)
                .pausedTotalMinutes(0)
                .responseRemainingMinutes(responseRemaining)
                .resolveRemainingMinutes(resolveRemaining)
                .responseBreached(now.isAfter(responseDeadline))
                .resolveBreached(now.isAfter(resolveDeadline))
                .build();
    }

    /**
     * 附件实体转视图。
     *
     * @param attachment 附件实体
     * @return 附件视图
     */
    private AttachmentVO toAttachmentVO(TicketAttachment attachment) {
        return AttachmentVO.builder()
                .id(attachment.getId())
                .fileName(attachment.getFileName())
                .fileUrl(attachment.getFileUrl())
                .fileSize(attachment.getFileSize())
                .mimeType(attachment.getMimeType())
                .createdAt(attachment.getCreatedAt())
                .build();
    }
}
