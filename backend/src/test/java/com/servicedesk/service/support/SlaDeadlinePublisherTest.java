package com.servicedesk.service.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.servicedesk.common.utils.SlaDelayQueue;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.sladelayqueue.SlaDelayQueueMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SLA deadline 投递器的单元测试。
 *
 * <p>
 * 属于业务支撑层测试。覆盖三条只有单测才好触发的分支：两个 deadline 各自成消息、
 * 空 deadline / 空工单不投递、Redis 不可用时吞掉异常交给分钟扫描兜底。
 * 时钟固定为 2026-11-27 10:00（东八区），断言换算出的 epoch 毫秒。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SlaDeadlinePublisherTest {

    /** 东八区，与 {@code server} 的固定时区一致。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定时钟：2026-11-27 10:00:00 +08:00。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T02:00:00Z"), ZONE);

    /** 延迟队列，仅验证被调用的消息内容，不写真实 Redis。 */
    @Mock
    private SlaDelayQueue slaDelayQueue;

    /** 被测投递器。 */
    private SlaDeadlinePublisher publisher;

    /**
     * 构造被测对象，时钟固定，队列用 mock。
     */
    @BeforeEach
    void setUp() {
        publisher = new SlaDeadlinePublisher(slaDelayQueue, FIXED_CLOCK);
    }

    /**
     * 两个 deadline 分别投递一条消息，类型与到期时间换算正确。
     */
    @Test
    @DisplayName("投递两个 deadline：响应与解决各一条消息")
    void shouldPublishBothDeadlines() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 27, 10, 0);
        Ticket ticket = ticketWithDeadlines(base.plusMinutes(30), base.plusHours(4));

        publisher.publish(ticket);

        ArgumentCaptor<SlaDelayQueueMessage> captor = ArgumentCaptor.forClass(SlaDelayQueueMessage.class);
        verify(slaDelayQueue, times(2)).enqueue(captor.capture());
        List<SlaDelayQueueMessage> messages = captor.getAllValues();
        assertThat(messages).extracting(SlaDelayQueueMessage::escalationType)
                .containsExactly("RESPONSE", "RESOLVE");
        assertThat(messages).extracting(SlaDelayQueueMessage::ticketId).containsOnly(7L);
        assertThat(messages.get(0).deadlineEpochMilli())
                .isEqualTo(base.plusMinutes(30).atZone(ZONE).toInstant().toEpochMilli());
        assertThat(messages.get(1).deadlineEpochMilli())
                .isEqualTo(base.plusHours(4).atZone(ZONE).toInstant().toEpochMilli());
    }

    /**
     * 只有解决 deadline 时只投递一条，空的响应 deadline 不产生消息。
     */
    @Test
    @DisplayName("空 deadline：跳过不投递")
    void shouldSkipNullDeadline() {
        Ticket ticket = ticketWithDeadlines(null, LocalDateTime.of(2026, 11, 27, 14, 0));

        publisher.publish(ticket);

        ArgumentCaptor<SlaDelayQueueMessage> captor = ArgumentCaptor.forClass(SlaDelayQueueMessage.class);
        verify(slaDelayQueue).enqueue(captor.capture());
        assertThat(captor.getValue().escalationType()).isEqualTo("RESOLVE");
    }

    /**
     * 工单或 ID 为空时不投递，避免把 {@code null} 写进队列。
     */
    @Test
    @DisplayName("空工单：不投递")
    void shouldIgnoreTicketWithoutId() {
        publisher.publish(null);
        publisher.publish(new Ticket());

        verifyNoInteractions(slaDelayQueue);
    }

    /**
     * Redis 不可用：异常被吞掉，不阻塞建单 / 编辑，第二条消息仍尝试投递。
     */
    @Test
    @DisplayName("Redis 失败：不抛异常，留给扫描兜底")
    void shouldSwallowRedisFailure() {
        doThrow(new RedisConnectionFailureException("redis down")).doNothing()
                .when(slaDelayQueue).enqueue(any());
        Ticket ticket = ticketWithDeadlines(
                LocalDateTime.of(2026, 11, 27, 10, 30), LocalDateTime.of(2026, 11, 27, 14, 0));

        assertThatCode(() -> publisher.publish(ticket)).doesNotThrowAnyException();

        verify(slaDelayQueue, times(2)).enqueue(any());
    }

    /**
     * 构造一张带 ID 与两个 deadline 的工单，不落库。
     *
     * @param responseDeadline 响应截止，可为 null
     * @param resolveDeadline  解决截止，可为 null
     * @return 工单实体
     */
    private Ticket ticketWithDeadlines(LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
        Ticket ticket = new Ticket();
        ticket.setId(7L);
        ticket.setResponseDeadline(responseDeadline);
        ticket.setResolveDeadline(resolveDeadline);
        return ticket;
    }
}
