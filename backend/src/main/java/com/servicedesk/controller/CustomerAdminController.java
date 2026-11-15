package com.servicedesk.controller;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.admin.CustomerCreateRequest;
import com.servicedesk.dto.request.admin.CustomerQueryRequest;
import com.servicedesk.dto.request.admin.CustomerUpdateRequest;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.security.RequiredRole;
import com.servicedesk.service.CustomerAdminService;
import com.servicedesk.vo.CustomerVO;
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
 * 客户档案接口，对应接口文档 9.8 - 9.10（M-03）。
 *
 * <p>
 * 属于接口层，只做参数接收与响应包装。主管与管理员可访问；报修人与工程师
 * 不能维护客户档案，只能在建单时选择已有客户。
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
@RequestMapping("/admin/customers")
@RequiredRole({ Role.SUPERVISOR, Role.ADMIN })
public class CustomerAdminController {

  /** 客户档案服务。 */
  private final CustomerAdminService customerAdminService;

  /**
   * 分页查询客户。
   *
   * @param query 查询条件，含关键字与分页参数
   * @return 分页结果
   */
  @GetMapping
  public Result<PageResult<CustomerVO>> page(@Valid CustomerQueryRequest query) {
    return Result.success(customerAdminService.page(query));
  }

  /**
   * 新建客户档案。
   *
   * @param request 新增入参
   * @return 新建客户
   */
  @PostMapping
  public Result<CustomerVO> create(@RequestBody @Valid CustomerCreateRequest request) {
    log.debug("收到新建客户请求, name={}, level={}", request.getName(), request.getLevel());
    return Result.success(customerAdminService.create(request));
  }

  /**
   * 修改客户档案。
   *
   * @param id      客户 ID
   * @param request 修改入参
   * @return 修改后的客户
   */
  @PutMapping("/{id}")
  public Result<CustomerVO> update(@PathVariable Long id, @RequestBody @Valid CustomerUpdateRequest request) {
    log.debug("收到修改客户请求, customerId={}, name={}", id, request.getName());
    return Result.success(customerAdminService.update(id, request));
  }
}
