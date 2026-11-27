package com.servicedesk.service.support;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.servicedesk.common.utils.SlaDelayQueue;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.sladelayqueue.SlaDelayQueueMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SLA deadline 投递器：把工单的响应 / 解决截止时间投递到 Redis 延迟队列。
 *
 * <p>
 * 属于业务支撑层，只做「实体 → 队列消息」的转换与失败降级，不判断工单状态、不写库：
 * 建单与编辑重算 deadline 后调用，消费端到点后仍会重新查库判定是否可以升级。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>只投递非空的 deadline，缺策略导致的空值不产生消息；</li>
 * <li>Redis 写入不参与 MySQL 事务（编码规范 4.3）：投递失败只记 warn，不回滚建单 / 编辑，
 * 由每分钟扫描兜底补齐——升级入口本身具备幂等，不会重复升级；</li>
 * <li>时刻换算统一用注入的 {@link Clock} 时区，禁止直接使用系统默认时区。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaDeadlinePublisher {

    /** 延迟队列，负责实际的 Redis 写入。 */
    private final SlaDelayQueue slaDelayQueue;

    /** 业务时钟，只用于把 {@link LocalDateTime} 换成本地时区的时间戳。 */
    private final Clock clock;

    /**
     * 投递工单的两个 deadline。
     *
     * <p>
     * 同一张单的同一类超时重复投递是安全的：队列成员由「工单 + 类型 + deadline」唯一确定，
     * 重复投递只更新 score。
     * </p>
     *
     * @param ticket 已完成建单或紧急度重算的工单实体，必须带 ID
     */
    public void publish(Ticket ticket) {
        if (ticket == null || ticket.getId() == null) {
            log.warn("SLA deadline 投递跳过：工单或工单 ID 为空");
            return;
        }
        publishOne(ticket.getId(), SlaDelayQueueMessage.TYPE_RESPONSE, ticket.getResponseDeadline());
        publishOne(ticket.getId(), SlaDelayQueueMessage.TYPE_RESOLVE, ticket.getResolveDeadline());
    }

    /**
     * 投递单个 deadline；失败只记 warn 并继续投递另一类，不把异常抛给建单 / 编辑。
     *
     * @param ticketId 工单 ID
     * @param type     升级类型，{@code RESPONSE} 或 {@code RESOLVE}
     * @param deadline 截止时间，为空时不投递
     */
    private void publishOne(Long ticketId, String type, LocalDateTime deadline) {
        if (deadline == null) {
            return;
        }
        long executeAtMillis = deadline.atZone(clock.getZone()).toInstant().toEpochMilli();
        try {
            slaDelayQueue.enqueue(new SlaDelayQueueMessage(ticketId, type, executeAtMillis));
        } catch (DataAccessException e) {
            // 队列只是准点触发的手段，扫描兜底仍在，因此不把异常抛给建单 / 编辑
            log.warn("SLA 延迟消息投递失败，本次由每分钟扫描兜底, ticketId={}, type={}", ticketId, type, e);
        }
    }
}
