package com.servicedesk.controller;

import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.sla.SlaPolicyCreateRequest;
import com.servicedesk.dto.request.sla.SlaPolicyUpdateRequest;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.security.RequiredRole;
import com.servicedesk.service.SlaPolicyService;
import com.servicedesk.vo.SlaPolicyVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SLA 策略接口，对应接口文档第 5 章 5.1 - 5.4（S-01）。
 *
 * <p>
 * 属于接口层，只做参数接收、{@code @Valid} 校验与响应包装。
 * 权限按接口文档逐方法声明：查询对登录用户开放（建单页需要展示 SLA 预览），
 * 增、改、删仅管理员，因此不能把注解标在类上——类注解是方法注解的替换而非追加。
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
@RequestMapping("/sla-policies")
public class SlaPolicyController {

  /** SLA 策略管理服务。 */
  private final SlaPolicyService slaPolicyService;

  /**
   * 查询全部 SLA 策略，按优先级排序。
   *
   * <p>
   * 不标注 {@code @RequiredRole}：登录即可访问，建单页的「响应 / 解决时限」预览依赖这份数据。
   * </p>
   *
   * @return 策略列表，按 P0 → P3 排列
   */
  @GetMapping
  public Result<List<SlaPolicyVO>> list() {
    return Result.success(slaPolicyService.list());
  }

  /**
   * 新增 SLA 策略。
   *
   * @param request 新增入参
   * @return 新建策略
   */
  @PostMapping
  @RequiredRole(Role.ADMIN)
  public Result<SlaPolicyVO> create(@RequestBody @Valid SlaPolicyCreateRequest request) {
    log.debug("收到新增 SLA 策略请求, name={}, priority={}, responseMinutes={}, resolveMinutes={}, enabled={}",
        request.getName(), request.getPriority(), request.getResponseMinutes(),
        request.getResolveMinutes(), request.getEnabled());
    return Result.success(slaPolicyService.create(request));
  }

  /**
   * 修改 SLA 策略。
   *
   * @param id      策略 ID
   * @param request 修改入参，不含优先级
   * @return 修改后的策略
   */
  @PutMapping("/{id}")
  @RequiredRole(Role.ADMIN)
  public Result<SlaPolicyVO> update(@PathVariable Long id,
      @RequestBody @Valid SlaPolicyUpdateRequest request) {
    log.debug("收到修改 SLA 策略请求, policyId={}, name={}, responseMinutes={}, resolveMinutes={}, enabled={}",
        id, request.getName(), request.getResponseMinutes(), request.getResolveMinutes(),
        request.getEnabled());
    return Result.success(slaPolicyService.update(id, request));
  }

  /**
   * 删除或停用 SLA 策略。
   *
   * <p>
   * 无工单引用时物理删除，有引用时仅停用；两种结果都返回成功，前端不需要区分。
   * </p>
   *
   * @param id 策略 ID
   * @return 无数据成功响应
   */
  @DeleteMapping("/{id}")
  @RequiredRole(Role.ADMIN)
  public Result<Void> delete(@PathVariable Long id) {
    log.debug("收到删除 SLA 策略请求, policyId={}", id);
    slaPolicyService.delete(id);
    return Result.success();
  }
}
