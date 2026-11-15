package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色枚举的单元测试。
 *
 * <p>
 * 属于枚举字典层测试，不启动 Spring 容器。重点守两件事：
 * 枚举名与数据库存储值、JWT 载荷之间的取值契约；以及任意非法输入都必须收敛成 401，
 * 不能变成 NPE 或 500。
 * </p>
 */
class RoleTest {

  /** 非法输入统一返回的提示语，与实现保持一致且不含用户输入原文。 */
  private static final String INVALID_ROLE_MESSAGE = "登录异常，请重新登录！";

  /** 业务状态码：登录态相关异常。 */
  private static final int UNAUTHORIZED = 401;

  /**
   * 四个常量名必须与 {@code sys_user.role} 的存储值逐字一致。
   *
   * <p>
   * 改名不会编译失败，只会让线上所有角色判断同时失效，因此必须由测试盯住。
   * </p>
   */
  @Test
  @DisplayName("枚举名与存储值：四个常量名逐字固定")
  void shouldKeepStorageCompatibleNames() {
    assertThat(Role.values())
        .extracting(Enum::name)
        .containsExactly("REPORTER", "ENGINEER", "SUPERVISOR", "ADMIN");
  }

  /**
   * 合法值往返：每个角色用自身 name() 解析都应还原为同一常量。
   */
  @Test
  @DisplayName("合法值：四个角色均可往返解析")
  void shouldResolveEveryRole() {
    for (Role role : Role.values()) {
      assertThat(Role.from(role.name())).isEqualTo(role);
    }
  }

  /**
   * 首尾空白与大小写不应导致解析失败：值可能来自手工改库、导入脚本或前端表单。
   */
  @Test
  @DisplayName("容错解析：首尾空白与大小写不敏感")
  void shouldNormalizeWhitespaceAndCase() {
    assertThat(Role.from(" engineer ")).isEqualTo(Role.ENGINEER);
    assertThat(Role.from("Admin")).isEqualTo(Role.ADMIN);
    assertThat(Role.from("\tSUPERVISOR ")).isEqualTo(Role.SUPERVISOR);
  }

  /**
   * null 与空白必须抛 401：空值意味着登录态不完整，属于服务端数据问题，不能静默返回 null。
   */
  @Test
  @DisplayName("空值：null、空串、纯空白均抛 401")
  void shouldRejectBlankValues() {
    assertUnauthorized(() -> Role.from(null));
    assertUnauthorized(() -> Role.from(""));
    assertUnauthorized(() -> Role.from("   "));
  }

  /**
   * 未知角色必须抛 401，且提示语不能回显用户输入。
   *
   * <p>
   * 回显会把外部输入带进响应体，前端渲染后存在注入面，排查所需信息应只留在日志里。
   * </p>
   */
  @Test
  @DisplayName("未知角色：401 且提示语不回显输入")
  void shouldRejectUnknownRoleWithoutEchoingInput() {
    assertThatThrownBy(() -> Role.from("ROOT"))
        .isInstanceOf(BusinessException.class)
        .hasMessageNotContaining("ROOT")
        .extracting("code")
        .isEqualTo(UNAUTHORIZED);
  }

  /**
   * 记录 JDK 行为：{@code Role.valueOf(null)} 抛的是 NPE，而不是 IllegalArgumentException。
   *
   * <p>
   * 这条用例是设计约束的证据：空值判断必须放在 try 之外，否则
   * {@code catch (IllegalArgumentException)} 接不住 null，最终冒泡成 500。
   * </p>
   */
  @Test
  @DisplayName("设计依据：valueOf(null) 抛 NPE，故判空必须在 try 外")
  void shouldProveNullIsNotIllegalArgumentException() {
    assertThatThrownBy(() -> Role.valueOf(null))
        .isInstanceOf(NullPointerException.class)
        .isNotInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 断言给定调用抛出 401 业务异常且提示语固定。
   *
   * @param callable 待执行逻辑
   */
  private void assertUnauthorized(ThrowingCallable callable) {
    assertThatThrownBy(callable)
        .isInstanceOf(BusinessException.class)
        .hasMessage(INVALID_ROLE_MESSAGE)
        .extracting("code")
        .isEqualTo(UNAUTHORIZED);
  }
}
