package com.servicedesk.service;

/**
 * SLA 超时升级服务（S-03），对应开发文档 7.2 的「响应超时 / 解决超时 / 升级幂等」三行规则。
 *
 * <p>
 * 属于业务层。只负责「把已经过期的工单标记为已升级并写时间线」，不发送通知、不改变工单状态：
 * {@code ESCALATED} 是标记 + 事件，不是第九个状态。
 * </p>
 *
 * <p>
 * 判定口径（与 {@code SlaStatusVO} 共用同一套语义）：
 * </p>
 * <ul>
 * <li>响应超时：{@code first_response_at} 为空、{@code response_deadline} 已过、非终态；</li>
 * <li>解决超时：{@code resolved_at} 为空、{@code resolve_deadline} 已过、非终态；</li>
 * <li>两类各自独立判定，同一张工单可能在同一轮写两条 {@code ESCALATE} 事件。</li>
 * </ul>
 */
public interface SlaEscalationService {

  /**
   * 扫描一轮超时工单并升级，由定时任务每分钟调用，也可在测试或补偿脚本中手动触发。
   *
   * <p>
   * 事务边界：整轮扫描一个事务，标记与事件要么同时写入、要么同时回滚；失败整体回滚，
   * 下一分钟重试，不会留下「标记了但时间线没有记录」。
   * </p>
   *
   * <p>
   * 幂等性：靠「{@code *_escalated_at IS NULL} 的条件更新」占位，影响行数为 1 才写事件，
   * 因此同一类超时重复扫描、多实例并发扫描都只会升级一次。
   * </p>
   *
   * @return 本轮实际升级的次数（响应 + 解决，含同一工单的两类）
   */
  int escalateOverdue();

  /**
   * 升级单张工单的某一类超时，供延迟队列消费端在 deadline 到点时调用。
   *
   * <p>
   * 与 {@link #escalateOverdue()} 共用同一套条件更新：方法内部重新读库并按
   * 「未升级 + 未响应 / 未解决 + 已过 deadline + 非终态」抢占，抢到才写事件。
   * 因此消息早到、重投、工单在投递后已被接单或取消，都只会得到 {@code false}，不会误升级。
   * </p>
   *
   * <p>
   * 事务边界：单条升级一个事务，标记与事件同生共死。
   * </p>
   *
   * @param ticketId       工单 ID
   * @param escalationType 升级类型，{@code RESPONSE} 或 {@code RESOLVE}
   * @return 抢到升级权并写入事件返回 {@code true}；工单不存在、类型非法、条件不满足返回 {@code false}
   */
  boolean escalateOne(Long ticketId, String escalationType);
}
