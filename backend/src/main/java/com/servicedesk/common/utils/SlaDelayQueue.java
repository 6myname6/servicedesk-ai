package com.servicedesk.common.utils;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.servicedesk.entity.sladelayqueue.DueMessage;
import com.servicedesk.entity.sladelayqueue.SlaDelayQueueMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SLA 延迟队列（Redis ZSET + LIST），把「到点的工单」准点交给消费端。
 *
 * <p>
 * 属于基础设施层，只负责消息搬运，不判断工单状态、不写库、不发送通知：
 * </p>
 * <ul>
 * <li>主队列 {@value #QUEUE_KEY}：ZSET，成员为 {@code ticketId:type:deadline}，score 为到期时间（epoch 毫秒）；</li>
 * <li>待消费队列 {@value #READY_KEY}：LIST，由 {@link #promoteDue(int)} 搬运已到期成员，消费端左侧取出；</li>
 * <li>死信队列 {@value #DEAD_KEY}：ZSET，格式非法或处理失败的消息转到这里，只用于人工排查，不自动重投。</li>
 * </ul>
 *
 * <p>
 * 关键约束：Redis 写入不参与 MySQL 事务（见编码规范 4.3）。生产端投递失败由调用方决定是补偿还是降级，
 * 本类不做吞异常处理；消费端取出的消息只保证「至少被取走一次」，业务幂等由升级的条件更新兜底。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaDelayQueue {
    /** 主队列 key：ZSET，score 为到期时间（epoch 毫秒）。 */
    private static final String QUEUE_KEY = "sla:delay:queue";
    /** 死信队列 key：格式非法或处理失败的消息转到这里，等人工排查。 */
    private static final String DEAD_KEY = "sla:delay:dead";
    /** 待消费队列 key：从主队列搬运过来的到期成员，消费端从这里取。 */
    private static final String READY_KEY = "sla:delay:ready";
    /** 消费端单批上限，防止一轮太多拉长循环 */
    private static final int BATCH = 200;
    private final StringRedisTemplate stringRedisTemplate;
    private final Clock clock;

    /** 到期成员搬运脚本：取到期成员->移除主队列->添加到待消费队列 */
    private static final DefaultRedisScript<Long> PROMOTE_SCRIPT = new DefaultRedisScript<>(
            """
                    local items = redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',ARGV[1],'LIMIT',0,ARGV[2],'WITHSCORES')
                    if #items == 0 then return 0 end
                    for i = 1,#items,2 do
                    redis.call('ZREM',KEYS[1],items[i])
                    redis.call('RPUSH',KEYS[2],items[i]..'|'..items[i+1])
                    end
                    return math.floor(#items / 2)
                    """, Long.class);

    /**
     * 将SLA延迟消息入队
     *
     * <p>
     * 同一成员重复投递只更新 score，天然幂等；Redis 不可用时异常向上抛出，由调用方决定降级方式。
     * </p>
     *
     * @param message SLA延迟消息
     */
    public void enqueue(SlaDelayQueueMessage message) {
        if (message == null) {
            log.warn("SLA延迟消息为空，忽略投递");
            return;
        }
        stringRedisTemplate.opsForZSet().add(QUEUE_KEY, message.toMember(), message.deadlineEpochMilli());
        log.info("SLA延迟消息入队，ticketId={}，type={}，executeAt={}", message.ticketId(), message.escalationType(),
                message.deadlineEpochMilli());
    }

    /**
     * 把主队列里已到期成员搬进待消费队列
     *
     * <p>
     * 搬运是「移除 + 追加」的原子脚本，多个实例同时执行也不会把同一条消息搬两次。
     * </p>
     *
     * @param limit 单轮最多搬运条数，按 1 ~ {@value #BATCH} 钳制
     * @return 实际搬入待消费队列的条数
     */
    public int promoteDue(int limit) {
        int batch = Math.max(1, Math.min(limit, BATCH));
        long nowMillis = clock.millis();
        Long promoted = stringRedisTemplate.execute(PROMOTE_SCRIPT, List.of(QUEUE_KEY, READY_KEY),
                String.valueOf(nowMillis), String.valueOf(batch));
        int count = promoted == null ? 0 : promoted.intValue();
        if (count > 0) {
            log.info("SLA到期消息搬运完成，count={}，now={}", count, nowMillis);
        }
        return count;
    }

    /**
     * 从待消费队列取一条消息
     *
     * <p>
     * 取走即从列表移除，不做确认回执：处理失败由调用方显式 {@link #deadLetter}，
     * 业务幂等由升级的条件更新保证。
     * </p>
     *
     * @param timeoutSeconds 阻塞等待秒数；小于等于 0 时非阻塞立即返回
     * @return 待消费消息；队列为空或阻塞超时返回 {@code null}
     */
    public DueMessage takeReady(long timeoutSeconds) {
        String payload = timeoutSeconds <= 0
                ? stringRedisTemplate.opsForList().leftPop(READY_KEY)
                : stringRedisTemplate.opsForList().leftPop(READY_KEY, timeoutSeconds, TimeUnit.SECONDS);
        if (payload == null) {
            return null;
        }
        return parseReady(payload);
    }

    /**
     * 把处理失败的消息转入死信队列，等待人工排查。
     *
     * <p>
     * 本类不自动重投：分钟扫描会兜底补齐同一条超时升级，转死信只为保留现场。
     * </p>
     *
     * @param message 处理失败的消息
     * @param reason  失败原因，只写日志，不进死信成员
     */
    public void deadLetter(SlaDelayQueueMessage message, String reason) {
        if (message == null) {
            log.warn("SLA死信消息为空，忽略转入死信队列，reason={}", reason);
            return;
        }
        stringRedisTemplate.opsForZSet().add(DEAD_KEY, message.toMember(), clock.millis());
        log.warn("SLA延迟消息转入死信，member={}，reason={}", message.toMember(), reason);
    }

    /**
     * 解析待消费队列中的一条原始成员。
     *
     * @param payload {@code ticketId:type:deadline|executeAt} 形式的原始成员
     * @return 解析后的消息；格式非法时转死信并返回 {@code null}
     */
    private DueMessage parseReady(String payload) {
        int split = payload.lastIndexOf('|');
        if (split <= 0 || split == payload.length() - 1) {
            log.warn("SLA待消费消息格式非法，转入死信，payload={}", payload);
            moveToDead(payload);
            return null;
        }
        SlaDelayQueueMessage message = SlaDelayQueueMessage.parse(payload.substring(0, split));
        Long executeAtMillis = parseScore(payload.substring(split + 1));
        if (message == null || executeAtMillis == null) {
            log.warn("SLA待消费消息不可解析，转入死信，payload={}", payload);
            moveToDead(payload);
            return null;
        }
        return new DueMessage(message, executeAtMillis);
    }

    /**
     * 解析成员记录的到期时间。
     *
     * @param scoreText 到期时间的字符串形式（epoch 毫秒）
     * @return 到期时间；非法数字返回 {@code null}，由调用方转死信
     */
    private Long parseScore(String scoreText) {
        try {
            return Long.parseLong(scoreText);
        } catch (NumberFormatException e) {
            log.warn("SLA待消费消息score非法，score={}", scoreText);
            return null;
        }
    }

    /**
     * 把无法解析的原始成员原样放入死信队列，保留现场供排查。
     *
     * @param payload 原始成员
     */
    private void moveToDead(String payload) {
        stringRedisTemplate.opsForZSet().add(DEAD_KEY, payload, clock.millis());
        log.warn("SLA消息进入死信队列，payload={}", payload);
    }
}
