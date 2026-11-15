package com.servicedesk.service;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.dto.request.admin.CustomerCreateRequest;
import com.servicedesk.dto.request.admin.CustomerQueryRequest;
import com.servicedesk.dto.request.admin.CustomerUpdateRequest;
import com.servicedesk.vo.CustomerVO;

/**
 * 客户档案服务，对应接口文档第 9 章 M-03。
 *
 * <p>
 * 属于业务层，只维护 {@code customer} 表。客户不做物理删除：设备与工单都通过
 * {@code customer_id} 逻辑引用客户，删除会留下悬空引用，因此接口契约只提供
 * 查询、新增、修改三个动作。
 * </p>
 *
 * <p>
 * 角色校验由接口层的 {@code @RequiredRole} 完成（主管与管理员可访问），
 * 客户档案是全局数据，不按团队过滤——团队数据范围只作用于工单。
 * </p>
 */
public interface CustomerAdminService {

  /**
   * 分页查询客户，支持关键字模糊匹配客户名或电话。
   *
   * <p>
   * 事务边界：只读，不加事务。按 ID 倒序，保证新增的客户排在前面。
   * </p>
   *
   * @param query 查询条件，分页参数已带默认值
   * @return 分页结果，无数据时 records 为空列表
   */
  PageResult<CustomerVO> page(CustomerQueryRequest query);

  /**
   * 新建客户档案。
   *
   * <p>
   * 事务边界：单表插入。幂等性：不幂等，重复提交会产生重复客户——客户名不做唯一约束，
   * 因为同一集团可能存在同名子公司，重复由人工在列表中识别并修正。
   * </p>
   *
   * @param request 新增入参
   * @return 新建客户
   * @throws com.servicedesk.common.exception.BusinessException 客户等级非法抛 400
   */
  CustomerVO create(CustomerCreateRequest request);

  /**
   * 修改客户档案（整体覆盖）。
   *
   * <p>
   * 事务边界：单表更新。
   * </p>
   *
   * @param id      客户 ID
   * @param request 修改入参
   * @return 修改后的客户
   * @throws com.servicedesk.common.exception.BusinessException 客户不存在抛 404；客户等级非法抛 400
   */
  CustomerVO update(Long id, CustomerUpdateRequest request);
}
