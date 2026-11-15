package com.servicedesk.controller;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.api.Result;
import com.servicedesk.dto.request.admin.UserCreateRequest;
import com.servicedesk.dto.request.admin.UserQueryRequest;
import com.servicedesk.dto.request.admin.UserStatusRequest;
import com.servicedesk.dto.request.admin.UserUpdateRequest;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.security.RequiredRole;
import com.servicedesk.service.UserAdminService;
import com.servicedesk.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口，对应接口文档第 9 章（M-01）。
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
@RequestMapping("/admin/users")
@RequiredRole(Role.ADMIN)
public class UserAdminController {

    /** 用户管理服务。 */
    private final UserAdminService userAdminService;

    /**
     * 分页查询用户。
     *
     * @param query 查询条件，含关键字、角色、状态与分页参数
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResult<UserVO>> page(@Valid UserQueryRequest query) {
        return Result.success(userAdminService.page(query));
    }

    /**
     * 新增用户。
     *
     * @param request 新增入参
     * @return 新建用户信息
     */
    @PostMapping
    public Result<UserVO> create(@RequestBody @Valid UserCreateRequest request) {
        log.debug("收到新增用户请求, username={}, role={}", request.getUsername(), request.getRole());
        return Result.success(userAdminService.create(request));
    }

    /**
     * 修改用户资料。
     *
     * @param id      用户 ID
     * @param request 修改入参
     * @return 修改后的用户信息
     */
    @PutMapping("/{id}")
    public Result<UserVO> update(@PathVariable Long id, @RequestBody @Valid UserUpdateRequest request) {
        log.debug("收到修改用户请求, userId={}, role={}", id, request.getRole());
        return Result.success(userAdminService.update(id, request));
    }

    /**
     * 启用或停用用户。
     *
     * @param id      用户 ID
     * @param request 目标状态
     * @return 无数据的成功响应
     */
    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody @Valid UserStatusRequest request) {
        log.debug("收到用户状态变更请求, userId={}, status={}", id, request.getStatus());
        userAdminService.updateStatus(id, request.getStatus());
        return Result.success();
    }
}
