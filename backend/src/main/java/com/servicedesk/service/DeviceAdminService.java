package com.servicedesk.service;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.dto.request.admin.DeviceCreateRequest;
import com.servicedesk.dto.request.admin.DeviceQueryRequest;
import com.servicedesk.dto.request.admin.DeviceUpdateRequest;
import com.servicedesk.vo.DeviceVO;

/**
 * 设备档案服务，对应接口文档第 9 章 M-03 的设备部分。
 *
 * <p>
 * 属于业务层，只维护 {@code device} 表。三条约定：
 * </p>
 *
 * <ul>
 * <li>序列号全局唯一，写入前显式检查并返回 409，不让唯一索引抛 500；</li>
 * <li>保修状态按保修到期日实时计算，不落库——落库会随日期推移变成过期数据；</li>
 * <li>设备不做物理删除：历史工单通过 {@code device_id} 引用设备，报废只改状态。</li>
 * </ul>
 *
 * <p>
 * 角色校验由接口层的 {@code @RequiredRole} 完成（主管与管理员可访问）。
 * </p>
 */
public interface DeviceAdminService {

  /**
   * 分页查询设备，支持关键字（型号/序列号）与客户过滤。
   *
   * <p>
   * 事务边界：只读，不加事务。按 ID 倒序，保证新增的设备排在前面；
   * 客户名按当前页批量回填，避免 N+1。
   * </p>
   *
   * @param query 查询条件，分页参数已带默认值
   * @return 分页结果，含实时计算的保修状态
   */
  PageResult<DeviceVO> page(DeviceQueryRequest query);

  /**
   * 新增设备档案。
   *
   * <p>
   * 事务边界：单表插入。幂等性：不幂等，重复提交会因序列号重复在第二次返回 409。
   * </p>
   *
   * @param request 新增入参
   * @return 新建设备，状态未指定时为 ACTIVE
   * @throws com.servicedesk.common.exception.BusinessException 客户不存在抛 400；序列号重复抛 409；
   *                                                           状态非法或保修到期早于购买日期抛 400
   */
  DeviceVO create(DeviceCreateRequest request);

  /**
   * 修改设备档案（型号、日期、位置整体覆盖）。
   *
   * <p>
   * 事务边界：单表更新。状态例外：未传时保留原状态，避免编辑弹窗漏传
   * 把已报废设备改回在用。
   * </p>
   *
   * @param id      设备 ID
   * @param request 修改入参
   * @return 修改后的设备
   * @throws com.servicedesk.common.exception.BusinessException 设备不存在抛 404；客户不存在抛 400；
   *                                                           序列号与其他设备重复抛 409；
   *                                                           状态非法或保修到期早于购买日期抛 400
   */
  DeviceVO update(Long id, DeviceUpdateRequest request);
}
