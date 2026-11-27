package com.servicedesk.entity.sladelayqueue;

/**
 * 延迟队列中一条已到期待消费的消息。
 *
 * <p>
 * 属于消息契约层：{@link #message} 是业务消息，{@link #executeAtMillis} 是它被投递时记录的到期时刻，
 * 两者分开保留，便于排查「消息何时该执行、实际何时被取走」。
 * </p>
 *
 * @param message         业务消息：工单 ID、升级类型与 deadline
 * @param executeAtMillis 到期时刻（epoch 毫秒），来自主队列的 score
 */
public record DueMessage(SlaDelayQueueMessage message, long executeAtMillis) {

}
