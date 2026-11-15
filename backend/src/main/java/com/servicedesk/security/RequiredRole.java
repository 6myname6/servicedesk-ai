package com.servicedesk.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.servicedesk.entity.enums.Role;

/**
 * 接口级角色校验注解：标注后仅允许指定角色访问该接口。
 *
 * <p>
 * 属于鉴权基础设施层的声明式注解，由 {@link RoleInterceptor} 在鉴权拦截器之后读取并校验。
 * 标在类上表示整个 Controller 的默认策略，标在方法上表示覆盖类上的配置——是替换，不是追加，
 * 因此「类上给 ADMIN、方法上给 SUPERVISOR」的语义是该方法的角色要求变成只有主管。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>{@code value()} 没有默认值，必须显式填写：漏填直接编译失败，避免出现「标了却等于没标」的空策略；</li>
 * <li>空数组（{@code @RequiredRole({})}）视为配置错误，按拒绝处理（fail-closed），绝不解释为不限角色；</li>
 * <li>未标注解表示「登录即可访问」，并不放开未登录访问——登录校验由 {@code AuthInterceptor} 负责。</li>
 * </ul>
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredRole {
    /**
     * 允许访问的角色列表，至少一个。
     *
     * <p>
     * 用枚举而非字符串，拼错在编译期暴露；空数组表示配置错误，由拦截器按 403 拒绝。
     * </p>
     *
     * @return 允许访问该接口的角色数组
     */
    Role[] value();

}
