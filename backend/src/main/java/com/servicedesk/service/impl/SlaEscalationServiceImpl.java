package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.TicketEventType;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.entity.sladelayqueue.SlaDelayQueueMessage;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.SlaEscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 超时升级实现（S-03），对应开发文档 7.2 与接口文档第 5 章。
 *
 * <p>
 * 属于业务实现层，有两个入口，共用同一套「先占位、再写事件」的链路：
 * </p>
 * <ol>
 * <li>{@link #escalateOne(Long, String)}：延迟队列消费端在 deadline 到点时逐条调用（准点路径）；</li>
 * <li>{@link #escalateOverdue()}：每分钟扫描兜底，补偿投递失败、Redis 不可用与历史数据（补偿路径）。</li>
 * </ol>
 *
 * <p>
 * 单条链路固定为三步：
 * </p>
 * <ol>
 * <li>按索引 {@code idx_sla_response} / {@code idx_sla_resolve}
 * 扫出本轮已过期且未升级的工单；</li>
 * <li>逐条做条件更新，把 {@code *_escalated_at} 置为当前时刻，影响行数为 1 才算抢到升级权；</li>
 * <li>抢到升级权才写 {@code ESCALATE} 事件，{@code operator_id} 留空表示系统事件。</li>
 * </ol>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>幂等：条件更新带 {@code *_escalated_at IS NULL}，重复扫描、重复投递与多实例并发都只成功一次；
 * 同时带上「已过 deadline、未响应 / 未解决、非终态」，避免队列消息早到或期间刚被处理的工单被误升级；</li>
 * <li>终态：{@code CLOSED} / {@code REJECTED} / {@code CANCELLED} 停止计时，不再升级；
 * 判定统一取 {@link TicketStatus#isTerminal()} 派生的
 * {@link TicketStatus#TERMINAL_NAMES}，
 * 不在此处另列状态名，避免与读模型口径分叉；</li>
 * <li>批量：单轮每类最多 {@value #BATCH_SIZE} 条，积压超过上限时下一分钟继续，避免长事务；</li>
 * <li>事务：整轮扫描一个事务、单条升级一个事务，标记与事件同生共死；失败整体回滚，下一分钟重试；</li>
 * <li>时间：一律取注入的 {@link Clock}，禁止无参 {@code now()}，便于单测固定时刻。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaEscalationServiceImpl implements SlaEscalationService {

    /** 单轮每类超时最多处理的条数：防止历史欠账一次性拉起长事务，超出的下一分钟继续。 */
    private static final int BATCH_SIZE = 200;

    /** 工单 Mapper，用于扫描超时工单与条件更新升级时间。 */
    private final TicketMapper ticketMapper;

    /** 工单事件 Mapper，升级只追加 {@code ESCALATE} 事件，不改历史事件。 */
    private final TicketEventMapper ticketEventMapper;

    /** JSON 工具，只用于拼 {@code metadata}，不解析业务正文。 */
    private final ObjectMapper objectMapper;

    /** 业务时钟，扫描基准与升级时间取它，禁止无参 {@code now()}。 */
    private final Clock clock;

    /**
     * 扫描并升级本轮全部超时工单。
     *
     * <p>
     * 由 {@code @Scheduled} 每分钟第 0 秒触发；方法本身也可被测试或补偿脚本直接调用，
     * 两者走同一套事务与幂等逻辑。
     * </p>
     *
     * @return 本轮实际升级的次数（响应 + 解决）
     */
    @Override
    @Scheduled(cron = "0  * * * * *", zone = "Asia/Shanghai")
    @Transactional(rollbackFor = Exception.class)
    public int escalateOverdue() {
        // 基准时刻只取一次并截到秒：扫描条件、升级标记与事件时间必须同源
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        int responseCount = escalateResponse(now);
        int resolveCount = escalateResolve(now);
        int total = responseCount + resolveCount;
        if (total == 0) {
            // 绝大多数轮次没有超时单，用 debug 避免每分钟刷一条 info
            log.debug("SLA 超时扫描完成，本轮无新增升级, now={}", now);
        } else {
            log.info("SLA 超时扫描完成, responseCount={}, resolveCount={}, now={}",
                    responseCount, resolveCount, now);
        }
        return total;
    }

    /**
     * 升级单张工单的某一类超时（延迟队列准点路径）。
     *
     * <p>
     * 先读一次工单拿到被突破的 deadline（事件正文要用），再交给与扫描完全相同的条件更新抢占；
     * 抢占失败一律安静跳过：消息早到、工单已被接单 / 解决 / 取消、已被另一路径升级，都属于正常情况。
     * </p>
     *
     * <p>
     * 事务边界：单条升级一个事务，标记与事件同生共死；由消费端通过接口调用，走 Spring 代理。
     * </p>
     *
     * @param ticketId       工单 ID
     * @param escalationType 升级类型，{@code RESPONSE} 或 {@code RESOLVE}
     * @return 抢到升级权并写入事件返回 {@code true}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean escalateOne(Long ticketId, String escalationType) {
        if (ticketId == null) {
            log.warn("SLA 延迟消息工单 ID 为空，忽略");
            return false;
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            // 建单事务回滚、工单被物理删除后消息仍可能留在队列里，属于可预期的脏消息
            log.warn("SLA 延迟消息指向的工单不存在，忽略, ticketId={}, type={}", ticketId, escalationType);
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (SlaDelayQueueMessage.TYPE_RESPONSE.equals(escalationType)) {
            if (!claimResponse(ticketId, now)) {
                return false;
            }
            ticketEventMapper.insert(buildEscalateEvent(
                    ticketId, SlaDelayQueueMessage.TYPE_RESPONSE, "响应", ticket.getResponseDeadline(), now));
            return true;
        }
        if (SlaDelayQueueMessage.TYPE_RESOLVE.equals(escalationType)) {
            if (!claimResolve(ticketId, now)) {
                return false;
            }
            ticketEventMapper.insert(buildEscalateEvent(
                    ticketId, SlaDelayQueueMessage.TYPE_RESOLVE, "解决", ticket.getResolveDeadline(), now));
            return true;
        }
        log.warn("SLA 延迟消息升级类型非法，忽略, ticketId={}, type={}", ticketId, escalationType);
        return false;
    }

    /**
     * 扫描并升级响应超时的工单。
     *
     * @param now 本轮扫描基准时刻
     * @return 实际升级条数
     */
    private int escalateResponse(LocalDateTime now) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .select(Ticket::getId, Ticket::getResponseDeadline)
                .isNull(Ticket::getResponseEscalatedAt)
                .isNull(Ticket::getFirstResponseAt)
                .isNotNull(Ticket::getResponseDeadline)
                .lt(Ticket::getResponseDeadline, now)
                .notIn(Ticket::getStatus, TicketStatus.TERMINAL_NAMES)
                .orderByAsc(Ticket::getResponseDeadline)
                .last("LIMIT " + BATCH_SIZE);
        List<Ticket> overdue = ticketMapper.selectList(wrapper);

        int escalated = 0;
        for (Ticket ticket : overdue) {
            if (!claimResponse(ticket.getId(), now)) {
                continue;
            }
            ticketEventMapper.insert(buildEscalateEvent(
                    ticket.getId(), SlaDelayQueueMessage.TYPE_RESPONSE, "响应",
                    ticket.getResponseDeadline(), now));
            escalated++;
        }
        return escalated;
    }

    /**
     * 扫描并升级解决超时的工单。
     *
     * @param now 本轮扫描基准时刻
     * @return 实际升级条数
     */
    private int escalateResolve(LocalDateTime now) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .select(Ticket::getId, Ticket::getResolveDeadline)
                .isNull(Ticket::getResolveEscalatedAt)
                .isNull(Ticket::getResolvedAt)
                .isNotNull(Ticket::getResolveDeadline)
                .lt(Ticket::getResolveDeadline, now)
                .notIn(Ticket::getStatus, TicketStatus.TERMINAL_NAMES)
                .orderByAsc(Ticket::getResolveDeadline)
                .last("LIMIT " + BATCH_SIZE);
        List<Ticket> overdue = ticketMapper.selectList(wrapper);

        int escalated = 0;
        for (Ticket ticket : overdue) {
            if (!claimResolve(ticket.getId(), now)) {
                continue;
            }
            ticketEventMapper.insert(buildEscalateEvent(
                    ticket.getId(), SlaDelayQueueMessage.TYPE_RESOLVE, "解决",
                    ticket.getResolveDeadline(), now));
            escalated++;
        }
        return escalated;
    }

    /**
     * 抢占一次响应超时升级权。
     *
     * <p>
     * 条件更新是幂等的唯一屏障：扫描到写入之间工单可能已被接单、取消或由其他实例升级，
     * 因此这里把「未升级 + 未响应 + 已过 deadline + 非终态」全部重复一遍，
     * 只有影响行数为 1 才说明本轮成功抢到。
     * </p>
     *
     * @param ticketId 工单 ID
     * @param now      升级时刻
     * @return 抢到升级权返回 true
     */
    private boolean claimResponse(Long ticketId, LocalDateTime now) {
        int updated = ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                .eq(Ticket::getId, ticketId)
                .isNull(Ticket::getResponseEscalatedAt)
                .isNull(Ticket::getFirstResponseAt)
                .lt(Ticket::getResponseDeadline, now)
                .notIn(Ticket::getStatus, TicketStatus.TERMINAL_NAMES)
                .set(Ticket::getResponseEscalatedAt, now));
        if (updated == 0) {
            log.debug("响应超时升级未抢到，跳过（已被处理或已被升级）, ticketId={}", ticketId);
        }
        return updated == 1;
    }

    /**
     * 抢占一次解决超时升级权。
     *
     * @param ticketId 工单 ID
     * @param now      升级时刻
     * @return 抢到升级权返回 true
     */
    private boolean claimResolve(Long ticketId, LocalDateTime now) {
        int updated = ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                .eq(Ticket::getId, ticketId)
                .isNull(Ticket::getResolveEscalatedAt)
                .isNull(Ticket::getResolvedAt)
                .lt(Ticket::getResolveDeadline, now)
                .notIn(Ticket::getStatus, TicketStatus.TERMINAL_NAMES)
                .set(Ticket::getResolveEscalatedAt, now));
        if (updated == 0) {
            log.debug("解决超时升级未抢到，跳过（已被处理或已被升级）, ticketId={}", ticketId);
        }
        return updated == 1;
    }

    /**
     * 构造 {@code ESCALATE} 事件。
     *
     * <p>
     * {@code operator_id} 留空表示系统事件；正文给人读，{@code metadata} 给前端做结构化展示：
     * {@code escalationType} 区分响应 / 解决，{@code deadline} 与 {@code overdueMinutes}
     * 固定为升级发生时的快照，后续策略调整不改写历史事件。
     * </p>
     *
     * @param ticketId 工单 ID
     * @param type     升级类型，{@code RESPONSE} 或 {@code RESOLVE}
     * @param label    中文标签，用于正文
     * @param deadline 被突破的截止时间
     * @param now      升级时刻
     * @return 待插入的事件实体
     */
    private TicketEvent buildEscalateEvent(Long ticketId, String type, String label,
            LocalDateTime deadline, LocalDateTime now) {
        long overdueMinutes = Duration.between(deadline, now).toMinutes();
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setType(TicketEventType.ESCALATE.name());
        event.setOperatorId(null);
        event.setContent(label + "超时 " + overdueMinutes + " 分钟，已自动升级");
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("escalationType", type);
        metadata.put("deadline", deadline.toString());
        metadata.put("overdueMinutes", overdueMinutes);
        event.setMetadata(metadata.toString());
        event.setCreatedAt(now);
        log.info("工单超时升级, ticketId={}, type={}, deadline={}, overdueMinutes={}",
                ticketId, type, deadline, overdueMinutes);
        return event;
    }
}
