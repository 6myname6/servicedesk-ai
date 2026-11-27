package com.servicedesk.service.impl;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.servicedesk.common.utils.SlaDelayQueue;
import com.servicedesk.entity.sladelayqueue.DueMessage;
import com.servicedesk.entity.sladelayqueue.SlaDelayQueueMessage;
import com.servicedesk.service.SlaDelayQueueConsumer;
import com.servicedesk.service.SlaEscalationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SLA 延迟队列消费实现（S-03 准点路径）。
 *
 * <p>
 * 属于业务实现层，只做三件事：搬运到期成员、循环取消息、交给升级服务。核心约束：
 * </p>
 * <ul>
 * <li>调度：{@code @Scheduled} 固定延迟（默认 1 秒，可用
 * {@code servicedesk.sla.delay-queue.poll-interval-ms} 覆盖），与分钟扫描共用调度线程，
 * 因此取消息必须非阻塞，不能占用整秒；</li>
 * <li>批量：单轮上限 {@value #BATCH} 条，积压留到下一轮，避免长时间占用线程；</li>
 * <li>失败：单条升级抛异常时转入死信队列并记 error，不中断本轮；消息已从待消费队列取出，
 * 不会自动重投，同一条超时升级由分钟扫描兜底；</li>
 * <li>幂等：不在这里判状态，全部交给 {@link SlaEscalationService#escalateOne(Long, String)}
 * 的条件更新，重复消息只会得到 {@code false}。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaDelayQueueConsumerImpl implements SlaDelayQueueConsumer {

    /** 单轮最多处理的消息条数：防止积压时长时间占用调度线程，剩余留到下一轮。 */
    private static final int BATCH = 200;

    /** 延迟队列，负责搬运到期成员与取消息。 */
    private final SlaDelayQueue slaDelayQueue;

    /** 升级服务，单条升级与扫描共用同一套幂等条件更新。 */
    private final SlaEscalationService slaEscalationService;

    /**
     * 搬运并消费一轮到期消息。
     *
     * @return 本轮实际升级的条数
     */
    @Override
    @Scheduled(fixedDelayString = "${servicedesk.sla.delay-queue.poll-interval-ms:1000}")
    public int consumeDue() {
        int promoted = slaDelayQueue.promoteDue(BATCH);
        int handled = 0;
        int escalated = 0;
        DueMessage due;
        while (handled < BATCH && (due = slaDelayQueue.takeReady(0)) != null) {
            handled++;
            if (escalate(due)) {
                escalated++;
            }
        }
        if (promoted > 0 || handled > 0) {
            log.info("SLA 延迟队列轮询完成, promoted={}, handled={}, escalated={}",
                    promoted, handled, escalated);
        }
        return escalated;
    }

    /**
     * 升级单条到期消息，失败时转死信。
     *
     * @param due 到期消息
     * @return 抢到升级权返回 {@code true}
     */
    private boolean escalate(DueMessage due) {
        SlaDelayQueueMessage message = due.message();
        try {
            return slaEscalationService.escalateOne(message.ticketId(), message.escalationType());
        } catch (RuntimeException e) {
            log.error("SLA 延迟消息处理失败，转入死信, member={}", message.toMember(), e);
            try {
                slaDelayQueue.deadLetter(message, e.getClass().getSimpleName());
            } catch (RuntimeException deadEx) {
                // Redis 同时不可用时连死信都写不进去：只记日志，升级由分钟扫描兜底
                log.error("SLA 消息转入死信失败，等待扫描兜底, member={}", message.toMember(), deadEx);
            }
            return false;
        }
    }
}
