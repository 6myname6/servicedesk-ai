package com.servicedesk.security;

import com.servicedesk.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录失败限流器的集成测试。
 *
 * <p>
 * 属于鉴权基础设施层测试，启动 Spring 容器以复用真实 Redis 连接与真实 Lua 脚本，
 * 不走 HTTP 层，因此只验证「计数 → 判定 → 清零」这条限流链路本身；
 * 接口层 401/429 的响应差异属于 {@code AuthControllerTest} 的范围。
 * </p>
 *
 * <p>
 * 阈值与窗口刻意使用字面量而非引用被测类的私有常量：这些数值是对外承诺的行为契约，
 * 实现里改错了测试必须能发现，因此不能跟着实现一起漂移。
 * 每个用例使用独立的用户名与 IP，结束后逐个删除计数键，避免用例之间、以及与开发环境互相污染。
 * </p>
 */
@SpringBootTest
class LoginRateLimiterTest {

  /** 账号维度计数键前缀，与实现逐字一致。 */
  private static final String USER_KEY_PREFIX = "auth:loginFail:user:";

  /** IP 维度计数键前缀，与实现逐字一致。 */
  private static final String IP_KEY_PREFIX = "auth:loginFail:ip:";

  /** 账号维度的锁定阈值。 */
  private static final int MAX_USER_ATTEMPTS = 5;

  /** IP 维度的锁定阈值，高于账号维度以容忍办公网共用出口 IP。 */
  private static final int MAX_IP_ATTEMPTS = 20;

  /** 固定窗口长度（秒）。 */
  private static final long WINDOW_SECONDS = 300L;

  /** 被测限流器。 */
  @Autowired
  private LoginRateLimiter loginRateLimiter;

  /** Redis 模板，用于读取计数、TTL 以及清理用例数据。 */
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  /** 本轮用例用过的用户名，结束后删除其账号维度计数键。 */
  private final List<String> usedUsernames = new ArrayList<>();

  /** 本轮用例用过的 IP，结束后删除其 IP 维度计数键。 */
  private final List<String> usedIpAddresses = new ArrayList<>();

  /**
   * 清理用例产生的两个维度的计数键，保证重复执行结果一致。
   */
  @AfterEach
  void tearDown() {
    usedUsernames.forEach(username -> stringRedisTemplate.delete(USER_KEY_PREFIX + username));
    usedIpAddresses.forEach(ip -> stringRedisTemplate.delete(IP_KEY_PREFIX + ip));
    usedUsernames.clear();
    usedIpAddresses.clear();
  }

  /**
   * 账号维度未达阈值时必须放行，避免正常用户输错几次就被拦下。
   */
  @Test
  @DisplayName("账号维度：连续失败 4 次仍可继续尝试")
  void shouldNotLockUserBeforeThreshold() {
    String username = newUsername();
    String clientIp = newClientIp();

    for (int i = 0; i < MAX_USER_ATTEMPTS - 1; i++) {
      loginRateLimiter.recordFailure(username, clientIp);
    }

    assertThatCode(() -> loginRateLimiter.checkUserLocked(username)).doesNotThrowAnyException();
  }

  /**
   * 账号维度达到阈值后必须拒绝，且状态码为文档约定的 429，而不是 401。
   */
  @Test
  @DisplayName("账号维度：第 5 次失败后锁定并返回 429")
  void shouldLockUserAtThreshold() {
    String username = newUsername();
    String clientIp = newClientIp();

    for (int i = 0; i < MAX_USER_ATTEMPTS; i++) {
      loginRateLimiter.recordFailure(username, clientIp);
    }

    assertThatThrownBy(() -> loginRateLimiter.checkUserLocked(username))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(429);
  }

  /**
   * 登录成功必须清零账号维度计数，否则「失败若干次后成功」的账号会被历史失败拖累。
   */
  @Test
  @DisplayName("账号维度：登录成功后计数清零并重新从 1 开始")
  void shouldResetUserCounterOnSuccess() {
    String username = newUsername();
    String clientIp = newClientIp();
    for (int i = 0; i < MAX_USER_ATTEMPTS - 1; i++) {
      loginRateLimiter.recordFailure(username, clientIp);
    }

    loginRateLimiter.clearOnSuccess(username);

    assertThat(stringRedisTemplate.hasKey(USER_KEY_PREFIX + username)).isFalse();
    assertThatCode(() -> loginRateLimiter.checkUserLocked(username)).doesNotThrowAnyException();

    loginRateLimiter.recordFailure(username, clientIp);
    assertThat(stringRedisTemplate.opsForValue().get(USER_KEY_PREFIX + username)).isEqualTo("1");
  }

  /**
   * 窗口长度必须是 5 分钟（300 秒）。
   *
   * <p>
   * 这条用例专门防止「毫秒值当秒传」的单位错误：一旦把 300000 传进按秒生效的 EXPIRE，
   * 窗口会变成约 3.5 天，仅凭肉眼与人工登录完全测不出来。
   * </p>
   */
  @Test
  @DisplayName("窗口长度：计数键 TTL 落在 (0, 300] 秒")
  void shouldExpireWithinWindow() {
    String username = newUsername();
    String clientIp = newClientIp();

    loginRateLimiter.recordFailure(username, clientIp);

    assertThat(stringRedisTemplate.getExpire(USER_KEY_PREFIX + username, TimeUnit.SECONDS))
        .isNotNull()
        .isBetween(1L, WINDOW_SECONDS);
    assertThat(stringRedisTemplate.getExpire(IP_KEY_PREFIX + clientIp, TimeUnit.SECONDS))
        .isNotNull()
        .isBetween(1L, WINDOW_SECONDS);
  }

  /**
   * 反复检查不应续期，否则窗口会退化成滑动窗口，攻击者可以无限试探。
   */
  @Test
  @DisplayName("重复检查不续期：多次 checkUserLocked 不延长 TTL")
  void shouldNotRenewWindowOnCheck() {
    String username = newUsername();
    String clientIp = newClientIp();
    loginRateLimiter.recordFailure(username, clientIp);
    long before = Objects.requireNonNullElse(
        stringRedisTemplate.getExpire(USER_KEY_PREFIX + username, TimeUnit.SECONDS), 0L);

    loginRateLimiter.checkUserLocked(username);
    loginRateLimiter.checkUserLocked(username);

    long after = Objects.requireNonNullElse(
        stringRedisTemplate.getExpire(USER_KEY_PREFIX + username, TimeUnit.SECONDS), 0L);
    assertThat(after).isLessThanOrEqualTo(before);
  }

  /**
   * 计数键被写入非数字内容时必须自愈，不能让脏数据把登录接口拖成 500。
   */
  @Test
  @DisplayName("脏数据：非数字计数被清除并放行")
  void shouldPurgeDirtyCounter() {
    String username = newUsername();
    stringRedisTemplate.opsForValue().set(USER_KEY_PREFIX + username, "not-a-number");

    assertThatCode(() -> loginRateLimiter.checkUserLocked(username)).doesNotThrowAnyException();

    assertThat(stringRedisTemplate.hasKey(USER_KEY_PREFIX + username)).isFalse();
  }

  /**
   * IP 维度必须能挡住「换用户名撞库」：每个账号只失败一次，账号维度永远触发不了。
   */
  @Test
  @DisplayName("IP 维度：同一 IP 换 20 个用户名后锁定并返回 429")
  void shouldLockIpAcrossDifferentUsernames() {
    String clientIp = newClientIp();

    for (int i = 0; i < MAX_IP_ATTEMPTS; i++) {
      loginRateLimiter.recordFailure(newUsername(), clientIp);
    }

    assertThatThrownBy(() -> loginRateLimiter.checkIpLocked(clientIp))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(429);
  }

  /**
   * 两个维度独立计数：一个 IP 上的少量失败不应影响其他 IP，账号计数也不应串到 IP 上。
   */
  @Test
  @DisplayName("双维度独立：一次失败同时写两个键，且互不影响")
  void shouldCountUserAndIpIndependently() {
    String username = newUsername();
    String clientIp = newClientIp();

    loginRateLimiter.recordFailure(username, clientIp);

    assertThat(stringRedisTemplate.opsForValue().get(USER_KEY_PREFIX + username)).isEqualTo("1");
    assertThat(stringRedisTemplate.opsForValue().get(IP_KEY_PREFIX + clientIp)).isEqualTo("1");

    // 距离阈值还很远，两个维度都应放行
    assertThatCode(() -> loginRateLimiter.checkUserLocked(username)).doesNotThrowAnyException();
    assertThatCode(() -> loginRateLimiter.checkIpLocked(clientIp)).doesNotThrowAnyException();

    // 账号维度清零不得顺手清掉 IP 维度，否则持有一个可用账号即可反复重置 IP 配额
    loginRateLimiter.clearOnSuccess(username);
    assertThat(stringRedisTemplate.hasKey(USER_KEY_PREFIX + username)).isFalse();
    assertThat(stringRedisTemplate.opsForValue().get(IP_KEY_PREFIX + clientIp)).isEqualTo("1");
  }

  /**
   * 生成用例专属用户名，并登记以便结束后清理。
   *
   * @return 带纳秒后缀的用户名，保证与其他用例及开发环境数据不冲突
   */
  private String newUsername() {
    String username = "ut_" + System.nanoTime();
    usedUsernames.add(username);
    return username;
  }

  /**
   * 生成用例专属客户端地址并登记。
   *
   * <p>
   * IP 在这里只是计数键的一段文本，用纳秒串保证唯一性，
   * 比伪造真实网段更能避免与其他用例、其他测试类串键。
   * </p>
   *
   * @return 唯一的客户端地址标识
   */
  private String newClientIp() {
    String clientIp = "utip_" + System.nanoTime();
    usedIpAddresses.add(clientIp);
    return clientIp;
  }
}
