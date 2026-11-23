package com.servicedesk.service;

import com.servicedesk.dto.request.sla.SlaPolicyCreateRequest;
import com.servicedesk.dto.request.sla.SlaPolicyUpdateRequest;
import com.servicedesk.vo.SlaPolicyVO;

import java.util.List;

/**
 * SLA 策略管理服务，对应开发文档 S-01 与接口文档第 5 章 5.1 - 5.4。
 *
 * <p>
 * 属于业务层，只维护 {@code sla_policy} 表。策略不存具体截止时间，只存两个时限的分钟数，
 * 改动只影响之后创建的工单（已建工单的 deadline 在建单时已落库），因此本服务的写操作
 * 不需要回写 {@code ticket} 表。
 * </p>
 *
 * <p>
 * 角色校验由接口层的 {@code @RequiredRole} 完成（查询登录即可，写操作仅管理员），
 * 本层不再判断角色，避免同一规则在两处实现。
 * </p>
 */
public interface SlaPolicyService {

  /**
   * 查询全部 SLA 策略，按优先级升序。
   *
   * <p>
   * 事务边界：只读，不加事务。策略为十级数据，一次性返回全量，不分页。
   * </p>
   *
   * @return 策略列表，按 priority 字典序（P0 → P3）排列，无数据时为空列表
   */
  List<SlaPolicyVO> list();

  /**
   * 新增 SLA 策略。
   *
   * <p>
   * 事务边界：单表插入。幂等性：不幂等，同一优先级重复提交第二次返回 409。
   * 唯一性先查后写，并发由 {@code uk_priority} 兜底并同样转 409。
   * </p>
   *
   * @param request 新增入参
   * @return 新建策略
   * @throws com.servicedesk.common.exception.BusinessException 优先级不在字典内抛 400；
   *                                                           解决时限小于响应时限抛 400；
   *                                                           该优先级已有策略抛 409
   */
  SlaPolicyVO create(SlaPolicyCreateRequest request);

  /**
   * 修改策略名、两个时限与启用状态（优先级不可改）。
   *
   * <p>
   * 事务边界：单表更新。幂等性：重复提交同一份入参结果一致。
   * </p>
   *
   * @param id      策略 ID
   * @param request 修改入参
   * @return 修改后的策略
   * @throws com.servicedesk.common.exception.BusinessException 策略不存在抛 404；
   *                                                           解决时限小于响应时限抛 400
   */
  SlaPolicyVO update(Long id, SlaPolicyUpdateRequest request);

  /**
   * 删除或停用策略：无工单引用时物理删除，有引用时仅停用。
   *
   * <p>
   * 事务边界：单表查询 + 单表写入，需要事务保证「判定引用」与「删除/停用」之间不被插入新工单引用。
   * 幂等性：对已停用且仍有引用的策略重复调用返回成功，不重复写库。
   * </p>
   *
   * <p>
   * 之所以不直接物理删除：{@code ticket.sla_policy_id} 是逻辑外键，删掉被引用的策略会让历史工单
   * 查不到策略名，因此改为停用——停用后新单不再匹配，历史工单不受影响（开发文档 7.2、7.3）。
   * </p>
   *
   * @param id 策略 ID
   * @throws com.servicedesk.common.exception.BusinessException 策略不存在抛 404
   */
  void delete(Long id);
}
