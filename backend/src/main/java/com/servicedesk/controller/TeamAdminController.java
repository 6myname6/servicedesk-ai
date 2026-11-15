package com.servicedesk.controller;

import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.admin.TeamCreateRequest;
import com.servicedesk.dto.request.admin.TeamUpdateRequest;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.security.RequiredRole;
import com.servicedesk.service.TeamAdminService;
import com.servicedesk.vo.TeamVO;
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

import java.util.List;

/**
 * 团队管理接口，对应接口文档 9.5 - 9.7（M-02）。
 *
 * <p>
 * 属于接口层，只做参数接收与响应包装。整个控制器只对管理员开放，
 * 角色要求标在类上而不是逐个方法上——减少「新加方法忘了标注解」的机会。
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
@RequestMapping("/admin/teams")
@RequiredRole(Role.ADMIN)
public class TeamAdminController {

  /** 团队管理服务。 */
  private final TeamAdminService teamAdminService;

  /**
   * 查询全部团队（含主管姓名与成员数）。
   *
   * @return 团队列表
   */
  @GetMapping
  public Result<List<TeamVO>> list() {
    return Result.success(teamAdminService.list());
  }

  /**
   * 新建团队。
   *
   * @param request 新增入参
   * @return 新建团队
   */
  @PostMapping
  public Result<TeamVO> create(@RequestBody @Valid TeamCreateRequest request) {
    log.debug("收到新建团队请求, name={}, supervisorId={}", request.getName(), request.getSupervisorId());
    return Result.success(teamAdminService.create(request));
  }

  /**
   * 修改团队。
   *
   * @param id      团队 ID
   * @param request 修改入参
   * @return 修改后的团队
   */
  @PutMapping("/{id}")
  public Result<TeamVO> update(@PathVariable Long id, @RequestBody @Valid TeamUpdateRequest request) {
    log.debug("收到修改团队请求, teamId={}, name={}", id, request.getName());
    return Result.success(teamAdminService.update(id, request));
  }
}
