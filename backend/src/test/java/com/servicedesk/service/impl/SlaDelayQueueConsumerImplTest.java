package com.servicedesk.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.servicedesk.common.utils.SlaDelayQueue;
import com.servicedesk.entity.sladelayqueue.DueMessage;
import com.servicedesk.entity.sladelayqueue.SlaDelayQueueMessage;
import com.servicedesk.service.SlaDelayQueueConsumer;
import com.servicedesk.service.SlaEscalationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SLA 延迟队列消费端的单元测试。
 *
 * <p>
 * 属于业务实现层测试。队列与升级服务都用 mock，覆盖正常消费、未抢到升级权、
 * 单条失败转死信后继续、队列为空不触发升级、单轮 200 条上限五条分支。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SlaDelayQueueConsumerImplTest {

    /** 延迟队列，mock 掉 Redis。 */
    @Mock
    private SlaDelayQueue slaDelayQueue;

    /** 升级服务，mock 掉数据库。 */
    @Mock
    private SlaEscalationService slaEscalationService;

    /** 被测消费端。 */
    private SlaDelayQueueConsumer consumer;

    /**
     * 构造被测对象。
     */
    @BeforeEach
    void setUp() {
        consumer = new SlaDelayQueueConsumerImpl(slaDelayQueue, slaEscalationService);
    }

    /**
     * 两条到期消息都被升级，返回升级条数。
     */
    @Test
    @DisplayName("正常消费：逐条调用单条升级")
    void shouldEscalateEveryDueMessage() {
        DueMessage first = due(11L, "RESPONSE");
        DueMessage second = due(12L, "RESOLVE");
        when(slaDelayQueue.promoteDue(200)).thenReturn(2);
        when(slaDelayQueue.takeReady(0)).thenReturn(first, second, null);
        when(slaEscalationService.escalateOne(anyLong(), anyString())).thenReturn(true);

        int escalated = consumer.consumeDue();

        assertThat(escalated).isEqualTo(2);
        verify(slaEscalationService).escalateOne(11L, "RESPONSE");
        verify(slaEscalationService).escalateOne(12L, "RESOLVE");
    }

    /**
     * 消息早到或工单已被处理：升级服务返回 false，不计入升级数。
     */
    @Test
    @DisplayName("未抢到升级权：不计入升级数")
    void shouldNotCountSkippedMessage() {
        when(slaDelayQueue.promoteDue(200)).thenReturn(1);
        when(slaDelayQueue.takeReady(0)).thenReturn(due(21L, "RESPONSE"), (DueMessage) null);
        when(slaEscalationService.escalateOne(21L, "RESPONSE")).thenReturn(false);

        assertThat(consumer.consumeDue()).isZero();
    }

    /**
     * 单条升级抛异常：转死信并继续处理本轮剩余消息，不中断循环。
     */
    @Test
    @DisplayName("单条失败：转死信后继续处理")
    void shouldDeadLetterFailedMessageAndContinue() {
        DueMessage broken = due(31L, "RESPONSE");
        DueMessage healthy = due(32L, "RESPONSE");
        when(slaDelayQueue.promoteDue(200)).thenReturn(2);
        when(slaDelayQueue.takeReady(0)).thenReturn(broken, healthy, null);
        when(slaEscalationService.escalateOne(31L, "RESPONSE"))
                .thenThrow(new IllegalStateException("db down"));
        when(slaEscalationService.escalateOne(32L, "RESPONSE")).thenReturn(true);

        int escalated = consumer.consumeDue();

        assertThat(escalated).isEqualTo(1);
        ArgumentCaptor<SlaDelayQueueMessage> captor = ArgumentCaptor.forClass(SlaDelayQueueMessage.class);
        verify(slaDelayQueue).deadLetter(captor.capture(), anyString());
        assertThat(captor.getValue().ticketId()).isEqualTo(31L);
    }

    /**
     * 队列为空：不调用升级服务，返回 0。
     */
    @Test
    @DisplayName("队列为空：不触发升级")
    void shouldDoNothingWhenQueueEmpty() {
        when(slaDelayQueue.promoteDue(200)).thenReturn(0);
        when(slaDelayQueue.takeReady(0)).thenReturn(null);

        assertThat(consumer.consumeDue()).isZero();
        verifyNoInteractions(slaEscalationService);
    }

    /**
     * 积压超过单轮上限：只处理 200 条，剩余留给下一轮。
     */
    @Test
    @DisplayName("单轮上限：最多处理 200 条")
    void shouldStopAtBatchLimit() {
        when(slaDelayQueue.promoteDue(200)).thenReturn(300);
        when(slaDelayQueue.takeReady(0)).thenReturn(due(41L, "RESOLVE"));
        when(slaEscalationService.escalateOne(41L, "RESOLVE")).thenReturn(true);

        assertThat(consumer.consumeDue()).isEqualTo(200);
        verify(slaEscalationService, times(200)).escalateOne(eq(41L), eq("RESOLVE"));
        verify(slaDelayQueue, times(200)).takeReady(0);
    }

    /**
     * 构造一条到期消息。
     *
     * @param ticketId 工单 ID
     * @param type     升级类型
     * @return 到期消息
     */
    private DueMessage due(Long ticketId, String type) {
        return new DueMessage(new SlaDelayQueueMessage(ticketId, type, 1_800_000_000_000L),
                1_800_000_000_000L);
    }
}
