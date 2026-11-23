package com.servicedesk.service.support;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.api.PageResult;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.mapper.TicketAttachmentMapper;
import com.servicedesk.vo.AttachmentVO;
import com.servicedesk.vo.SlaStatusVO;
import com.servicedesk.vo.TicketDetailVO;
import com.servicedesk.vo.TicketEventVO;
import com.servicedesk.vo.TicketListItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工单读模型装配器：把工单实体翻译成详情（附录 A.8）、列表项（附录 A.7）与事件（附录 A.10）。
 *
 * <p>
 * 属于业务支撑层，只做「查关联 + 拼结构 + 算派生值」：不判权限、不判状态、不写库。
 * 工单是否存在、当前用户是否可见、内部备注是否可读，由调用方在进入本类前用
 * {@code TicketAccessGuard} 与角色判定。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>{@link #assembleDetail(Ticket, LocalDateTime)} 每条会发起 6 次单表查询，<b>禁止在循环里逐条调用</b>；
 * 列表场景用 {@link #assemblePage(IPage, LocalDateTime)}——名称一次批量回填，逐条只做内存组装；</li>
 * <li>事件场景用 {@link #assembleEvents(List)}——操作人一次批量回填，metadata 逐条解析，不产生 N+1；</li>
 * <li>其他批量场景（导出、报表）需要时再新增 {@code assembleBatch}，不要复用单条详情装配；</li>
 * <li>时间基准由调用方传入，同一条响应只能有一个 {@code now}，否则跨零点会出现两种口径。</li>
 * </ul>
 *
 * <p>
 * 依赖方向单向：本类只依赖 Mapper，不依赖任何 Service；业务编排仍在 {@code TicketServiceImpl}。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAssembler {

    /** 客户 Mapper，详情回填客户名与电话。 */
    private final CustomerMapper customerMapper;

    /** 设备 Mapper，详情回填型号、序列号与保修期。 */
    private final DeviceMapper deviceMapper;

    /** 用户 Mapper，回填报修人与处理人显示名。 */
    private final SysUserMapper sysUserMapper;

    /** 团队 Mapper，回填团队名。 */
    private final TeamMapper teamMapper;

    /** SLA 策略 Mapper，回填策略名；deadline 本身取工单上的冻结值。 */
    private final SlaPolicyMapper slaPolicyMapper;

    /** 附件 Mapper，详情回填附件列表。 */
    private final TicketAttachmentMapper attachmentMapper;

    /** JSON 工具，把事件的 metadata 原文解析成出参对象；解析失败按容错处理，不影响整条时间线。 */
    private final ObjectMapper objectMapper;

    /**
     * 把工单实体装配成详情视图（附录 A.8），建单、详情、编辑三个响应共用。
     *
     * <p>
     * 关联记录查不到时给 null 而不是抛 500，逻辑外键的脏数据（客户被删、策略被停用）
     * 不应该让整页失败。每次调用会发起 6 次单表查询，不要在循环里使用。
     * </p>
     *
     * @param ticket 已确认存在且在数据范围内的工单
     * @param now    本次响应的统一时刻，SLA 剩余与超时都以它为基准
     * @return 工单详情视图
     */
    public TicketDetailVO assembleDetail(Ticket ticket, LocalDateTime now) {
        Customer customer = customerMapper.selectById(ticket.getCustomerId());
        Device device = ticket.getDeviceId() == null ? null : deviceMapper.selectById(ticket.getDeviceId());
        SysUser reporter = sysUserMapper.selectById(ticket.getReporterId());
        SysUser assignee = ticket.getAssigneeId() == null ? null
                : sysUserMapper.selectById(ticket.getAssigneeId());
        Team team = ticket.getTeamId() == null ? null : teamMapper.selectById(ticket.getTeamId());
        List<TicketAttachment> attachments = attachmentMapper.selectList(
                Wrappers.<TicketAttachment>lambdaQuery()
                        .eq(TicketAttachment::getTicketId, ticket.getId())
                        .orderByAsc(TicketAttachment::getId));

        return TicketDetailVO.builder()
                .id(ticket.getId())
                .ticketNo(ticket.getTicketNo())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .category(ticket.getCategory())
                .urgency(ticket.getUrgency())
                .status(ticket.getStatus())
                .customer(customer == null ? null
                        : new TicketDetailVO.CustomerBrief(
                                customer.getId(), customer.getName(), customer.getPhone()))
                .device(device == null ? null
                        : new TicketDetailVO.DeviceBrief(
                                device.getId(), device.getModel(), device.getSerialNo(), device.getWarrantyEnd()))
                .reporter(reporter == null ? null
                        : new TicketDetailVO.UserBrief(reporter.getId(), reporter.getDisplayName()))
                .assignee(assignee == null ? null
                        : new TicketDetailVO.UserBrief(assignee.getId(), assignee.getDisplayName()))
                .team(team == null ? null : new TicketDetailVO.TeamBrief(team.getId(), team.getName()))
                .sla(buildSlaDetail(ticket, now))
                .ai(null)
                .attachments(attachments.stream().map(this::toAttachmentVO).toList())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .version(ticket.getVersion() == null ? 0 : ticket.getVersion())
                .build();
    }

    /**
     * 装配分页的列表项（附录 A.7），名称按当前页一次批量回填。
     *
     * <p>
     * 这是列表场景的批量入口：先 {@link #loadNames(List)} 一次查齐四类名称，再逐条做纯内存映射，
     * 不会产生 N+1。真正需要导出等其它批量形态时，另新增 {@code assembleBatch}。
     * </p>
     *
     * @param page 已按数据范围查出的分页结果
     * @param now  本次请求时刻，用于计算解决剩余分钟
     * @return 记录已转换为列表项的分页响应
     */
    public PageResult<TicketListItemVO> assemblePage(IPage<Ticket> page, LocalDateTime now) {
        NameIndex names = loadNames(page.getRecords());
        return PageResult.from(page, ticket -> toItemVO(ticket, names, now));
    }

    /**
     * 批量装配事件视图（附录 A.10），时间线与追加处理记录的响应共用。
     *
     * <p>
     * 先收集操作人 ID 一次批量查用户，再逐条解析 metadata 做内存映射，不会产生 N+1；
     * 空列表不查库。本方法只做结构转换，内部备注的可见性过滤由调用方在查询阶段完成。
     * </p>
     *
     * @param events 已按可见性与时间顺序排好的事件
     * @return 与入参顺序一致的事件视图
     */
    public List<TicketEventVO> assembleEvents(List<TicketEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        Set<Long> operatorIds = events.stream()
                .map(TicketEvent::getOperatorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SysUser> operators = operatorIds.isEmpty() ? Map.of()
                : sysUserMapper.selectByIds(operatorIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        return events.stream()
                .map(event -> toEventVO(event, event.getOperatorId() == null
                        ? null : operators.get(event.getOperatorId())))
                .toList();
    }

    /**
     * 事件实体转视图。
     *
     * @param event    事件实体
     * @param operator 操作人，系统事件或账号已删除时为 null
     * @return 事件视图
     */
    private TicketEventVO toEventVO(TicketEvent event, SysUser operator) {
        EventMetadata metadata = parseMetadata(event);
        return TicketEventVO.builder()
                .id(event.getId())
                .type(event.getType())
                .fromStatus(event.getFromStatus())
                .toStatus(event.getToStatus())
                .operatorId(event.getOperatorId())
                .operatorName(operator == null ? null : operator.getDisplayName())
                .operatorRole(operator == null ? null : operator.getRole())
                .content(event.getContent())
                .internal(metadata.internal())
                .metadata(metadata.values())
                .createdAt(event.getCreatedAt())
                .build();
    }

    /**
     * 解析事件的 metadata 原文。
     *
     * <p>
     * metadata 由本系统写入，正常都是对象；非对象或 JSON 损坏时按「内部备注」处理并返回空扩展，
     * 宁可少展示也不把可能含内部信息的事件误判为公开。解析失败只记事件 ID 与类型，不记正文。
     * </p>
     *
     * @param event 事件实体
     * @return 解析结果，不会为 null
     */
    private EventMetadata parseMetadata(TicketEvent event) {
        if (!StringUtils.hasText(event.getMetadata())) {
            return new EventMetadata(false, null);
        }
        try {
            JsonNode node = objectMapper.readTree(event.getMetadata());
            if (node == null || !node.isObject()) {
                log.warn("工单事件 metadata 不是对象, 按空扩展处理, eventId={}, type={}",
                        event.getId(), event.getType());
                return new EventMetadata(false, null);
            }
            Map<String, Object> values = objectMapper.convertValue(node,
                    new TypeReference<Map<String, Object>>() {
                    });
            return new EventMetadata(node.path("internal").asBoolean(false), values);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("工单事件 metadata 解析失败, 按内部备注处理, eventId={}, type={}",
                    event.getId(), event.getType());
            return new EventMetadata(true, null);
        }
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
            // RC-001 后没有暂停口径，列表与详情都按当前时刻计算
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
     * 组装 SLA 状态（附录 A.9）。
     *
     * <p>
     * deadline 直接取工单上的冻结值，绝不在读路径上按策略重算；剩余分钟与超时状态分开计算，
     * 因为 {@code Duration.toMinutes()} 向零截断，剩余 0 分钟与超时不足 1 分钟无法区分。
     * 终态（CLOSED / REJECTED / CANCELLED）不计超时；RC-001 后暂停字段恒为 false / 0。
     * </p>
     *
     * @param ticket 工单实体
     * @param now    本次请求时刻
     * @return SLA 状态；策略与两个 deadline 全为空的历史数据返回 null
     */
    private SlaStatusVO buildSlaDetail(Ticket ticket, LocalDateTime now) {
        if (ticket.getSlaPolicyId() == null
                && ticket.getResponseDeadline() == null
                && ticket.getResolveDeadline() == null) {
            return null;
        }
        SlaPolicy policy = ticket.getSlaPolicyId() == null
                ? null : slaPolicyMapper.selectById(ticket.getSlaPolicyId());
        TicketStatus status = TicketStatus.from(ticket.getStatus());
        boolean terminal = status == TicketStatus.CLOSED
                || status == TicketStatus.REJECTED
                || status == TicketStatus.CANCELLED;

        long responseRemaining = ticket.getResponseDeadline() == null ? 0
                : Duration.between(now, ticket.getResponseDeadline()).toMinutes();
        long resolveRemaining = ticket.getResolveDeadline() == null ? 0
                : Duration.between(now, ticket.getResolveDeadline()).toMinutes();
        boolean responseBreached = !terminal
                && ticket.getFirstResponseAt() == null
                && ticket.getResponseDeadline() != null
                && now.isAfter(ticket.getResponseDeadline());
        boolean resolveBreached = !terminal
                && ticket.getResolvedAt() == null
                && ticket.getResolveDeadline() != null
                && now.isAfter(ticket.getResolveDeadline());

        return SlaStatusVO.builder()
                .policyId(ticket.getSlaPolicyId())
                .policyName(policy == null ? null : policy.getName())
                .responseDeadline(ticket.getResponseDeadline())
                .resolveDeadline(ticket.getResolveDeadline())
                .firstResponseAt(ticket.getFirstResponseAt())
                // 暂停机制已移除：字段保留只为兼容前端，恒 false / 0
                .paused(false)
                .pausedTotalMinutes(0)
                .responseRemainingMinutes(responseRemaining)
                .resolveRemainingMinutes(resolveRemaining)
                .responseBreached(responseBreached)
                .resolveBreached(resolveBreached)
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

    /**
     * 事件 metadata 的解析结果。
     *
     * @param internal 是否内部备注；metadata 损坏时按 true 处理
     * @param values   原始扩展字段，无扩展信息或解析失败时为 null
     */
    private record EventMetadata(boolean internal, Map<String, Object> values) {
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
}
