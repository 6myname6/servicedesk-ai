package com.servicedesk.security;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色校验拦截器的单元测试。
 *
 * <p>
 * 属于鉴权基础设施层测试，不启动 Spring 容器：直接用测试用 Controller 构造
 * {@link HandlerMethod}，再手动写入 {@link UserContext}，从而精确控制四种注解声明方式与角色组合。
 * </p>
 *
 * <p>
 * 覆盖重点：类注解与方法注解的优先级（方法为替换而非追加）、空角色数组的 fail-closed、
 * 未登录时先抛 401、以及非 HandlerMethod 处理器必须直接放行。
 * </p>
 */
class RoleInterceptorTest {

  /** 业务状态码：未登录。 */
  private static final int UNAUTHORIZED = 401;

  /** 业务状态码：角色不足。 */
  private static final int FORBIDDEN = 403;

  /** 测试用用户 ID。 */
  private static final Long USER_ID = 9L;

  /** 被测拦截器，本身无依赖，可直接实例化。 */
  private final RoleInterceptor interceptor = new RoleInterceptor();

  /** 每个用例结束后清理线程上下文，避免身份串到下一个用例。 */
  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  /**
   * 处理器不是 HandlerMethod（静态资源、/error 等）时必须直接放行。
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("非 HandlerMethod：直接放行且不抛异常")
  void shouldPassThroughForNonHandlerMethod() throws Exception {
    boolean allowed = interceptor.preHandle(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

    assertThat(allowed).isTrue();
  }

  /**
   * 未标注解的接口对全部登录角色开放。
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("无注解：任意登录角色放行")
  void shouldAllowUnannotatedEndpoint() throws Exception {
    loginAs(Role.ENGINEER);

    assertThat(intercept(handlerOf(new OpenController(), "open"))).isTrue();
  }

  /**
   * 类级注解命中时放行。
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("类注解命中：管理员放行")
  void shouldAllowRoleDeclaredOnClass() throws Exception {
    loginAs(Role.ADMIN);

    assertThat(intercept(handlerOf(new ClassLevelController(), "list"))).isTrue();
  }

  /**
   * 类级注解未命中时拒绝。
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("类注解未命中：工程师返回 403")
  void shouldRejectRoleNotDeclaredOnClass() throws Exception {
    loginAs(Role.ENGINEER);

    assertForbidden(() -> intercept(handlerOf(new ClassLevelController(), "list")));
  }

  /**
   * 方法注解优先于类注解：方法里声明的角色可以进入。
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("方法注解优先：方法声明的角色放行")
  void shouldAllowRoleDeclaredOnMethod() throws Exception {
    loginAs(Role.ENGINEER);

    assertThat(intercept(handlerOf(new OverrideController(), "engineerOnly"))).isTrue();
  }

  /**
   * 方法注解是「替换」而不是「追加」：类上允许但方法上没写的角色必须被拒绝。
   *
   * <p>
   * 这是本套机制最容易写错的一条：若实现成合并，收紧权限的操作实际会变成放宽权限。
   * </p>
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("方法注解覆盖：类上允许但方法未写的角色返回 403")
  void shouldRejectRoleAllowedOnlyByClass() throws Exception {
    loginAs(Role.ADMIN);

    assertForbidden(() -> intercept(handlerOf(new OverrideController(), "engineerOnly")));
  }

  /**
   * 空角色数组视为配置错误，任何人（含管理员）都应被拒绝。
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("空角色数组：管理员也返回 403（fail-closed）")
  void shouldRejectEveryoneWhenRolesEmpty() throws Exception {
    loginAs(Role.ADMIN);

    assertForbidden(() -> intercept(handlerOf(new EmptyRuleController(), "emptyRule")));
  }

  /**
   * 上下文没有登录用户时（鉴权拦截器漏配或顺序颠倒）必须抛 401 而不是放行。
   *
   * <p>
   * 这条同时钉住了两个拦截器的顺序依赖：角色校验必须排在鉴权之后，
   * 反之所有受限接口都会 401。
   * </p>
   *
   * @throws Exception 拦截器调用异常
   */
  @Test
  @DisplayName("无登录上下文：受限接口抛 401 而不是放行")
  void shouldRejectWhenUserContextMissing() throws Exception {
    assertThatThrownBy(() -> intercept(handlerOf(new ClassLevelController(), "list")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(UNAUTHORIZED);
  }

  /**
   * 执行一次拦截。
   *
   * @param handlerMethod 处理器
   * @return 拦截器返回值
   * @throws Exception 拦截器调用异常
   */
  private boolean intercept(HandlerMethod handlerMethod) throws Exception {
    return interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handlerMethod);
  }

  /**
   * 断言拦截结果为 403。
   *
   * @param callable 待执行逻辑
   */
  private void assertForbidden(ThrowingRunnable callable) {
    assertThatThrownBy(callable::run)
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(FORBIDDEN);
  }

  /**
   * 把指定角色写入线程上下文。
   *
   * @param role 角色
   */
  private void loginAs(Role role) {
    UserContext.set(new LoginUser(USER_ID, "role_test", "角色测试", role.name(), null));
  }

  /**
   * 由 Controller 实例与方法名构造 HandlerMethod。
   *
   * @param controller 控制器实例
   * @param methodName 方法名
   * @return 处理器方法
   */
  private HandlerMethod handlerOf(Object controller, String methodName) {
    Method method = findMethod(controller.getClass(), methodName);
    return new HandlerMethod(controller, method);
  }

  /**
   * 反射查找无参方法。
   *
   * @param type       控制器类型
   * @param methodName 方法名
   * @return 方法对象
   */
  private Method findMethod(Class<?> type, String methodName) {
    try {
      return type.getMethod(methodName);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("测试用 Controller 缺少方法: " + methodName, e);
    }
  }

  /** 无注解的控制器：登录即可访问。 */
  public static class OpenController {

    /** 无注解方法。 */
    public void open() {
      // 仅供拦截器测试，无业务逻辑
    }
  }

  /** 只在类上声明角色的控制器。 */
  @RequiredRole({Role.ADMIN, Role.SUPERVISOR})
  public static class ClassLevelController {

    /** 使用类级角色策略。 */
    public void list() {
      // 仅供拦截器测试，无业务逻辑
    }
  }

  /** 类上给一组角色、方法上给另一组角色的控制器，用于验证覆盖语义。 */
  @RequiredRole({Role.ADMIN, Role.SUPERVISOR})
  public static class OverrideController {

    /** 方法级注解应替换类级注解。 */
    @RequiredRole(Role.ENGINEER)
    public void engineerOnly() {
      // 仅供拦截器测试，无业务逻辑
    }
  }

  /** 空角色数组的控制器，用于验证 fail-closed。 */
  public static class EmptyRuleController {

    /** 注解未声明任何角色。 */
    @RequiredRole({})
    public void emptyRule() {
      // 仅供拦截器测试，无业务逻辑
    }
  }

  /** 便于在断言中包裹受检异常。 */
  @FunctionalInterface
  private interface ThrowingRunnable {

    /**
     * 执行可能抛异常的逻辑。
     *
     * @throws Exception 任意异常
     */
    void run() throws Exception;
  }
}
