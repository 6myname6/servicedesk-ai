package com.servicedesk.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.enums.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 接口级角色校验拦截器：读取 {@link RequiredRole} 并校验当前登录用户的角色。
 *
 * <p>
 * 属于鉴权基础设施层。执行顺序必须排在 {@code AuthInterceptor} 之后（由
 * {@code WebMvcConfig} 的 {@code order} 显式保证）：本类依赖它写入的 {@link UserContext}，
 * 顺序颠倒会让所有请求在取角色时抛 401。
 * </p>
 *
 * <p>
 * 与数据范围的分工：本类回答「这个角色能不能调这个接口」，失败返回 403；
 * 「能看到哪些行」由 {@code domain.ticket.TicketScope} 负责，越权返回 404。
 * </p>
 */
@Component
@Slf4j
public class RoleInterceptor implements HandlerInterceptor {

    /**
     * 请求进入前校验角色。
     *
     * <p>
     * 规则解析顺序：方法注解优先，其次类注解，都没有则放行（登录即可访问）。
     * 非 {@link HandlerMethod} 的请求（静态资源、{@code /error} 等）直接放行，
     * 否则强转会抛异常并掩盖真实的 401/403。
     * </p>
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param handler  处理器，可能是 {@link HandlerMethod} 或资源处理器
     * @return true 放行
     * @throws BusinessException 角色不足或注解配置为空时抛出，状态码 403
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;// 静态资源，error等等
        }
        RequiredRole rule = hm.getMethodAnnotation(RequiredRole.class);// 方法优先
        if (rule == null) {// 方法没有注解，尝试类上
            rule = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiredRole.class);
        }
        if (rule == null) {// 没有注解，放行
            return true;
        }
        Role[] required = rule.value();
        LoginUser user = UserContext.getRequired();
        Role current = Role.from(user.role());
        if (required.length == 0) {
            log.warn("权限注解为空，拒绝访问，uri={}", request.getRequestURI());
            throw new BusinessException(403, "权限不足，拒绝访问！");
        }
        if (!List.of(required).contains(current)) {
            log.warn("角色不足，uri={}，userId={}，role={}，required={}", request.getRequestURI(), user.id(), current,
                    Arrays.toString(required));
            throw new BusinessException(403, "权限不足，拒绝访问！");
        }
        return true;
    }
}
