package com.servicedesk.service;

/**
 * SLA 延迟队列消费服务（S-03 准点路径）。
 *
 * <p>
 * 属于业务层：由定时任务轮询，把已经到期的消息交给
 * {@link SlaEscalationService#escalateOne(Long, String)}，升级判定与幂等全部复用升级服务，
 * 本接口不重复实现业务规则。
 * </p>
 */
public interface SlaDelayQueueConsumer {

  /**
   * 搬运一轮到期消息并逐条升级。
   *
   * <p>
   * 单轮最多处理 200 条，避免长时间占用调度线程；取消息非阻塞，队列为空时立即返回。
   * 单条处理异常转入死信队列并记 error 日志，不打断本轮剩余消息；同一条超时升级仍会由
   * 每分钟扫描兜底。
   * </p>
   *
   * @return 本轮实际升级的条数（响应 + 解决）
   */
  int consumeDue();
}
