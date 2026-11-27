package com.servicedesk.common.utils;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.servicedesk.entity.sladelayqueue.DueMessage;
import com.servicedesk.entity.sladelayqueue.SlaDelayQueueMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 延迟队列的集成测试，走真实 Redis（本地 db0）。
 *
 * <p>
 * 属于基础设施层测试。不启动 Spring 上下文，手工建连接，避免应用内的消费端定时任务
 * 与本用例争抢待消费队列，保证「投递 → 搬运 → 取出」的断言是确定性的。
 * 三个队列键在用例前后各清一次，不依赖实例 flush。
 * </p>
 */
class SlaDelayQueueTest {

    /** 主队列键名，与 {@code SlaDelayQueue} 的常量保持一致。 */
    private static final String QUEUE_KEY = "sla:delay:queue";

    /** 待消费队列键名。 */
    private static final String READY_KEY = "sla:delay:ready";

    /** 死信队列键名。 */
    private static final String DEAD_KEY = "sla:delay:dead";

    /** 本用例独占的 Redis 连接。 */
    private LettuceConnectionFactory connectionFactory;

    /** 与生产同款的字符串模板，用于造数与检查死信。 */
    private StringRedisTemplate redis;

    /** 被测队列。 */
    private SlaDelayQueue queue;

    /**
     * 连接本地 Redis 并清空三个队列键。
     */
    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.setDatabase(0);
        connectionFactory.setPassword(System.getenv().getOrDefault("REDIS_PASSWORD", "123456"));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        queue = new SlaDelayQueue(redis, Clock.systemDefaultZone());
        deleteKeys();
    }

    /**
     * 清理队列键并释放连接。
     */
    @AfterEach
    void tearDown() {
        deleteKeys();
        connectionFactory.destroy();
    }

    /**
     * 已到期的消息被搬入待消费队列，取出后业务字段与到期时刻都完整。
     */
    @Test
    @DisplayName("到点消息：搬运后可取出")
    void shouldPromoteDueMessage() {
        long deadline = System.currentTimeMillis() - 1_000;
        queue.enqueue(new SlaDelayQueueMessage(101L, "RESPONSE", deadline));

        assertThat(queue.promoteDue(10)).isEqualTo(1);

        DueMessage due = queue.takeReady(0);
        assertThat(due).isNotNull();
        assertThat(due.message().ticketId()).isEqualTo(101L);
        assertThat(due.message().escalationType()).isEqualTo("RESPONSE");
        assertThat(due.executeAtMillis()).isEqualTo(deadline);
        assertThat(queue.takeReady(0)).isNull();
    }

    /**
     * 未到期的消息留在主队列，搬运与取消息都拿不到。
     */
    @Test
    @DisplayName("未到点消息：不搬运")
    void shouldNotPromoteFutureMessage() {
        queue.enqueue(new SlaDelayQueueMessage(102L, "RESOLVE", System.currentTimeMillis() + 60_000));

        assertThat(queue.promoteDue(10)).isZero();
        assertThat(queue.takeReady(0)).isNull();
    }

    /**
     * 同一成员重复投递只保留一条，不会因为建单重试而重复升级。
     */
    @Test
    @DisplayName("重复投递：同一成员只搬运一次")
    void shouldKeepSingleMemberOnDuplicateEnqueue() {
        long deadline = System.currentTimeMillis() - 1;
        queue.enqueue(new SlaDelayQueueMessage(103L, "RESPONSE", deadline));
        queue.enqueue(new SlaDelayQueueMessage(103L, "RESPONSE", deadline));

        assertThat(queue.promoteDue(10)).isEqualTo(1);
    }

    /**
     * 格式非法的成员：返回 null 并原样进死信队列。
     */
    @Test
    @DisplayName("非法成员：转死信")
    void shouldMoveMalformedPayloadToDeadQueue() {
        redis.opsForList().rightPush(READY_KEY, "not-a-message");

        assertThat(queue.takeReady(0)).isNull();

        Set<String> dead = redis.opsForZSet().range(DEAD_KEY, 0, -1);
        assertThat(dead).contains("not-a-message");
    }

    /**
     * 类型不在字典内的成员同样进死信，避免脏数据被当成合法消息升级。
     */
    @Test
    @DisplayName("类型非法：转死信")
    void shouldRejectUnknownEscalationType() {
        redis.opsForList().rightPush(READY_KEY, "105:BOGUS:1700000000000|1700000000000");

        assertThat(queue.takeReady(0)).isNull();
        assertThat(redis.opsForZSet().range(DEAD_KEY, 0, -1))
                .contains("105:BOGUS:1700000000000|1700000000000");
    }

    /**
     * 处理失败的消息按标准成员格式写入死信队列，便于人工重放。
     */
    @Test
    @DisplayName("处理失败：deadLetter 保留成员")
    void shouldStoreDeadLetterMessage() {
        queue.deadLetter(new SlaDelayQueueMessage(104L, "RESOLVE", 0L), "处理失败");

        assertThat(redis.opsForZSet().range(DEAD_KEY, 0, -1)).contains("104:RESOLVE:0");
    }

    /**
     * 删除三个队列键，保证用例之间互不影响。
     */
    private void deleteKeys() {
        redis.delete(List.of(QUEUE_KEY, READY_KEY, DEAD_KEY));
    }
}
