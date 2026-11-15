package com.servicedesk.controller;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.admin.DeviceCreateRequest;
import com.servicedesk.dto.request.admin.DeviceQueryRequest;
import com.servicedesk.dto.request.admin.DeviceUpdateRequest;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.security.RequiredRole;
import com.servicedesk.service.DeviceAdminService;
import com.servicedesk.vo.DeviceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备档案接口，对应接口文档 9.11 - 9.13（M-03）。
 *
 * <p>
 * 属于接口层，只做参数接收与响应包装。主管与管理员可维护设备档案；
 * 报修人与工程师只能在建单时选择已有设备。
 * </p>
 *
 * <p>
 * 路径不含 {@code /api}：{@code server.servlet.context-path} 在进入 DispatcherServlet
 * 前已被剥离。
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/devices")
@RequiredRole({ Role.SUPERVISOR, Role.ADMIN })
public class DeviceAdminController {

  /** 设备档案服务。 */
  private final DeviceAdminService deviceAdminService;

  /**
   * 分页查询设备。
   *
   * @param query 查询条件，含关键字、客户与分页参数
   * @return 分页结果
   */
  @GetMapping
  public Result<PageResult<DeviceVO>> page(@Valid DeviceQueryRequest query) {
    return Result.success(deviceAdminService.page(query));
  }

  /**
   * 新增设备档案。
   *
   * @param request 新增入参
   * @return 新建设备
   */
  @PostMapping
  public Result<DeviceVO> create(@RequestBody @Valid DeviceCreateRequest request) {
    log.debug("收到新增设备请求, customerId={}, serialNo={}", request.getCustomerId(), request.getSerialNo());
    return Result.success(deviceAdminService.create(request));
  }

  /**
   * 修改设备档案。
   *
   * @param id      设备 ID
   * @param request 修改入参
   * @return 修改后的设备
   */
  @PutMapping("/{id}")
  public Result<DeviceVO> update(@PathVariable Long id, @RequestBody @Valid DeviceUpdateRequest request) {
    log.debug("收到修改设备请求, deviceId={}, serialNo={}", id, request.getSerialNo());
    return Result.success(deviceAdminService.update(id, request));
  }
}
