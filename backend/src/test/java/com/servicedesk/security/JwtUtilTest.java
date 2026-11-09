package com.servicedesk.security;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发与解析的单元测试。
 *
 * <p>
 * 属于鉴权基础设施层测试，不启动 Spring 容器，直接用配置构造 {@link JwtUtil}。
 * 覆盖正常签发、篡改、过期、剩余有效期四类场景。
 * </p>
 */
class JwtUtilTest {

  /** 测试用密钥，长度满足 HS256 的 32 字节要求。 */
  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  /** 测试用有效期（秒）。 */
  private static final long EXPIRE_SECONDS = 3600L;

  /** 被测工具。 */
  private JwtUtil jwtUtil;

  /**
   * 每个用例前用固定配置重建工具，避免用例之间互相影响。
   */
  @BeforeEach
  void setUp() {
    jwtUtil = new JwtUtil(new JwtProperties(SECRET, EXPIRE_SECONDS));
  }

  /**
   * 正常签发后解析，五个字段应与签发时一致。
   */
  @Test
  @DisplayName("签发后解析：用户字段完整还原")
  void shouldRoundTripUserClaims() {
    LoginUser user = new LoginUser(2L, "zhanggong", "张工", "ENGINEER", 1L);

    LoginUser parsed = jwtUtil.parse(jwtUtil.generate(user));

    assertThat(parsed).isEqualTo(user);
  }

  /**
   * 团队为空的用户（例如管理员）也应能正常往返，teamId 为 null。
   */
  @Test
  @DisplayName("签发后解析：teamId 为空时不报错")
  void shouldSupportNullTeamId() {
    LoginUser user = new LoginUser(1L, "admin", "管理员", "ADMIN", null);

    LoginUser parsed = jwtUtil.parse(jwtUtil.generate(user));

    assertThat(parsed.teamId()).isNull();
    assertThat(parsed.role()).isEqualTo("ADMIN");
  }

  /**
   * 篡改 token 必须验签失败并返回 401。
   */
  @Test
  @DisplayName("篡改 token：验签失败返回 401")
  void shouldRejectTamperedToken() {
    String token = jwtUtil.generate(new LoginUser(2L, "zhanggong", "张工", "ENGINEER", 1L));

    assertThatThrownBy(() -> jwtUtil.parse(token + "tampered"))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(401);
  }

  /**
   * 有效期为负时签发出的 token 已过期，解析应返回 401。
   */
  @Test
  @DisplayName("过期 token：返回 401")
  void shouldRejectExpiredToken() {
    JwtUtil expiredJwtUtil = new JwtUtil(new JwtProperties(SECRET, -60L));
    String token = expiredJwtUtil.generate(new LoginUser(2L, "zhanggong", "张工", "ENGINEER", 1L));

    assertThatThrownBy(() -> expiredJwtUtil.parse(token))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(401);
  }

  /**
   * 剩余有效期应落在 0 与有效期之间，供登出时设置黑名单 TTL。
   */
  @Test
  @DisplayName("剩余有效期：在 0 与配置值之间")
  void shouldReturnRemainingSeconds() {
    String token = jwtUtil.generate(new LoginUser(2L, "zhanggong", "张工", "ENGINEER", 1L));

    assertThat(jwtUtil.remainingSeconds(token)).isBetween(1L, EXPIRE_SECONDS);
    assertThat(jwtUtil.getExpireSeconds()).isEqualTo(EXPIRE_SECONDS);
  }
}
