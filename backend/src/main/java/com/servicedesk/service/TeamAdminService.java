package com.servicedesk.service;

import com.servicedesk.dto.request.admin.TeamCreateRequest;
import com.servicedesk.dto.request.admin.TeamUpdateRequest;
import com.servicedesk.vo.TeamVO;

import java.util.List;

/**
 * 团队管理服务，对应接口文档第 9 章 M-02。
 *
 * <p>
 * 属于业务层，只维护 {@code team} 表与团队名唯一性；成员的增删与归属调整走
 * 用户管理接口（{@code UserAdminService}），本接口不直接改写 {@code sys_user}。
 * </p>
 *
 * <p>
 * 角色校验由接口层的 {@code @RequiredRole} 完成（本接口只对管理员开放），
 * 因此接口内部不再判断角色——避免同一规则在两处实现。
 * </p>
 */
public interface TeamAdminService {

  /**
   * 查询全部团队，附带主管姓名与成员数。
   *
   * <p>
   * 事务边界：只读，不加事务。团队为十级数据，一次性返回全量，不做分页，
   * 前端下拉框与列表共用同一份结果。
   * </p>
   *
   * @return 团队列表，无数据时为空列表，不会是 null
   */
  List<TeamVO> list();

  /**
   * 新建团队并指定主管。
   *
   * <p>
   * 事务边界：单表插入。幂等性：不幂等，重复调用会因团队名重复在第二次返回 409。
   * </p>
   *
   * @param request 新增入参
   * @return 新建团队，成员数为 0
   * @throws com.servicedesk.common.exception.BusinessException 团队名重复抛 409；主管不存在抛 400
   */
  TeamVO create(TeamCreateRequest request);

  /**
   * 修改团队名、主管与描述。
   *
   * <p>
   * 事务边界：单表更新。主管与描述按整体覆盖处理，未传即清空。
   * </p>
   *
   * @param id      团队 ID
   * @param request 修改入参
   * @return 修改后的团队，成员数为实时统计值
   * @throws com.servicedesk.common.exception.BusinessException 团队不存在抛 404；
   *                                                           团队名与其他团队重复抛 409；主管不存在抛 400
   */
  TeamVO update(Long id, TeamUpdateRequest request);
}
