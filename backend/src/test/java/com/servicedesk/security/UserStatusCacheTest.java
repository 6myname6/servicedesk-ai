package com.servicedesk.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 用户停用标记的集成测试。
 *
 * <p>
 * 属于鉴权基础设施层测试，启动 Spring 容器以复用真实 Redis。标记本身是安全判定的一部分，
 * 因此重点验证三件事：写入后能查到、清除后立即恢复、TTL 不超过 token 最长存活时间。
 * </p>
 */
@SpringBootTest
class UserStatusCacheTest {

  /** 停用标记键前缀，与实现逐字一致，用于直接断言 Redis 状态。 */
  private static final String KEY_PREFIX = "auth:user:disabled:";

  /**
   * token 最长存活秒数（7 天），对应 {@code jwt.expire-seconds} 的契约值。
   *
   * <p>
   * 这里写死字面量而不是读配置：标记 TTL 的语义是「旧 token 可能还在用的最长时间」，
   * 属于对外承诺，配置被误改时测试必须能发现。
   * </p>
   */
  private static final long TOKEN_MAX_SECONDS = 604800L;

  /** 被测缓存。 */
  @Autowired
  private UserStatusCache userStatusCache;

  /** Redis 模板，用于断言 TTL 与清理用例数据。 */
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  /** 本轮用例使用的用户 ID，结束后逐个清理。 */
  private final List<Long> usedUserIds = new ArrayList<>();

  /**
   * 清理用例产生的停用标记，保证重复执行结果一致。
   */
  @AfterEach
  void tearDown() {
    usedUserIds.forEach(id -> stringRedisTemplate.delete(KEY_PREFIX + id));
    usedUserIds.clear();
  }

  /**
   * 标记后必须能查到，否则停用不生效。
   */
  @Test
  @DisplayName("标记：停用后查询为已停用")
  void shouldMarkUserDisabled() {
    Long userId = newUserId();

    userStatusCache.markDisabled(userId);

    assertThat(userStatusCache.isDisabled(userId)).isTrue();
    assertThat(stringRedisTemplate.hasKey(KEY_PREFIX + userId)).isTrue();
  }

  /**
   * 清除后必须立即恢复，否则用户被启用后仍登不进去。
   */
  @Test
  @DisplayName("清除：启用后查询为未停用")
  void shouldClearDisabledFlag() {
    Long userId = newUserId();
    userStatusCache.markDisabled(userId);

    userStatusCache.clearDisabled(userId);

    assertThat(userStatusCache.isDisabled(userId)).isFalse();
    assertThat(stringRedisTemplate.hasKey(KEY_PREFIX + userId)).isFalse();
  }

  /**
   * TTL 不得超过 token 最长存活时间：超出意味着会留下永远无人回收的垃圾键，
   * 太短则会让被停用者在 token 仍有效时恢复访问。
   */
  @Test
  @DisplayName("TTL：落在 (0, token 最长存活时间] 内")
  void shouldExpireWithinTokenLifetime() {
    Long userId = newUserId();

    userStatusCache.markDisabled(userId);

    Long ttl = stringRedisTemplate.getExpire(KEY_PREFIX + userId, TimeUnit.SECONDS);
    assertThat(ttl).isNotNull().isBetween(1L, TOKEN_MAX_SECONDS);
  }

  /**
   * 重复标记必须幂等：管理员重复点「停用」不应报错。
   */
  @Test
  @DisplayName("幂等：重复标记不报错且状态保持")
  void shouldAllowRepeatedMark() {
    Long userId = newUserId();

    assertThatCode(() -> {
      userStatusCache.markDisabled(userId);
      userStatusCache.markDisabled(userId);
    }).doesNotThrowAnyException();
    assertThat(userStatusCache.isDisabled(userId)).isTrue();
  }

  /**
   * 未标记时清除必须幂等，避免「重复启用」把接口打成 500。
   */
  @Test
  @DisplayName("幂等：重复清除不报错")
  void shouldAllowRepeatedClear() {
    Long userId = newUserId();

    assertThatCode(() -> {
      userStatusCache.clearDisabled(userId);
      userStatusCache.clearDisabled(userId);
    }).doesNotThrowAnyException();
    assertThat(userStatusCache.isDisabled(userId)).isFalse();
  }

  /**
   * 生成用例专属用户 ID 并登记。
   *
   * <p>
   * 这些 ID 只用于构造 Redis 键，不落库，因此用纳秒串保证唯一即可。
   * </p>
   *
   * @return 唯一的用户 ID
   */
  private Long newUserId() {
    Long userId = System.nanoTime();
    usedUserIds.add(userId);
    return userId;
  }
}
