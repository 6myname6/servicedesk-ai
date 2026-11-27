package com.servicedesk.entity.sladelayqueue;

import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * SLA 延迟队列消息，对应开发文档 7.2 的两类超时升级。
 *
 * <p>
 * 属于消息契约层。队列成员固定为 {@code ticketId:type:deadline} 三段，便于在 Redis 命令行直接读：
 * </p>
 * <ul>
 * <li>{@code ticketId}：工单 ID；</li>
 * <li>{@code type}：{@link #TYPE_RESPONSE} 或 {@link #TYPE_RESOLVE}，与 {@code ESCALATE} 事件的
 * {@code metadata.escalationType} 取值一致；</li>
 * <li>{@code deadline}：投递时该工单的 deadline（epoch 毫秒），同时用作 ZSET 的 score。</li>
 * </ul>
 *
 * <p>
 * 消息只承载「哪张单、哪类超时、原定到点时刻」，升级与否由消费端重新查库判定，
 * 因此工单状态、deadline 在投递后发生变化都不影响正确性。
 * </p>
 *
 * @param ticketId           工单 ID
 * @param escalationType     升级类型，{@code RESPONSE} 或 {@code RESOLVE}
 * @param deadlineEpochMilli 到期时刻（epoch 毫秒）
 */
@Slf4j
public record SlaDelayQueueMessage(Long ticketId, String escalationType, long deadlineEpochMilli) {

    /** 升级类型：响应超时，对应 {@code metadata.escalationType=RESPONSE}。 */
    public static final String TYPE_RESPONSE = "RESPONSE";

    /** 升级类型：解决超时，对应 {@code metadata.escalationType=RESOLVE}。 */
    public static final String TYPE_RESOLVE = "RESOLVE";

    /** 合法的升级类型集合，解析时用于拦截手写或脏数据写入的非法成员。 */
    private static final Set<String> TYPES = Set.of(TYPE_RESPONSE, TYPE_RESOLVE);

    /**
     * 序列化为队列成员。
     *
     * @return {@code ticketId:type:deadline} 形式的成员
     */
    public String toMember() {
        return ticketId + ":" + escalationType + ":" + deadlineEpochMilli;
    }

    /**
     * 将队列成员(@see toMember)反序列化为SlaDelayQueueMessage对象
     *
     * <p>
     * 任一字段缺失、类型不在 {@link #TYPES} 内、数字不可解析都返回 {@code null}，
     * 由调用方转入死信队列，不抛异常打断消费循环。
     * </p>
     *
     * @param member 队列成员
     * @return SlaDelayQueueMessage对象；格式非法返回 {@code null}
     */
    public static SlaDelayQueueMessage parse(String member) {
        if (member == null || member.isBlank()) {
            log.warn("队列成员为空或空字符串");
            return null;
        }
        String[] parts = member.split(":", -1);
        if (parts.length != 3) {
            log.warn("队列成员格式不正确，member = {}", member);
            return null;
        }
        if (!TYPES.contains(parts[1])) {
            log.warn("SLA延迟消息格式错误，超时类型非法，member={}", member);
            return null;
        }
        try {
            return new SlaDelayQueueMessage(Long.parseLong(parts[0]), parts[1], Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            log.warn("队列成员解析失败，member = {}", member);
            return null;
        }
    }
}
