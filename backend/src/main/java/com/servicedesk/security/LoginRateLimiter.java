package com.servicedesk.security;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.common.utils.RedisAtomicCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流器，按「账号」与「客户端 IP」两个维度统计失败次数，抵御暴力破解。
 *
 * <p>
 * 属于鉴权基础设施层，只负责计数与锁定判定，不查库、不校验密码。
 * 两个维度独立计数、独立判定，缺一都会被绕过：
 * </p>
 *
 * <ul>
 * <li>账号维度：防止盯住某个账号反复撞密码；</li>
 * <li>IP 维度：防止用一个 IP 遍历海量用户名撞库，这类攻击下每个账号的失败次数都是 1。</li>
 * </ul>
 *
 * <p>
 * 固定窗口：计数键在窗口内首次创建时设置 TTL，后续自增不续期，窗口到期计数自然归零，
 * 由 Redis 的过期机制负责回收，本类不做定时清理。
 * </p>
 */
@Slf4j
@Component
public class LoginRateLimiter {

  /** 账号维度：同一账号在窗口内允许的最大失败次数。 */
  private static final int MAX_USER_FAIL_ATTEMPTS = 5;

  /**
   * IP 维度：同一 IP 在窗口内允许的最大失败次数。
   *
   * <p>
   * 取值明显高于账号维度：办公网经 NAT 出口时多个用户共用一个公网 IP，
   * 阈值过小会把正常同事一起锁在门外；同时也不能等于账号阈值，
   * 否则单用户输错几次就会连累整个出口 IP。
   * </p>
   */
  private static final int MAX_IP_FAIL_ATTEMPTS = 20;

  /** 固定窗口长度（秒），账号与 IP 两个维度共用。 */
  private static final int WINDOW_SECONDS = 300;

  /**
   * 锁定提示语。
   *
   * <p>
   * 固定文案，不回显用户名或 IP：响应体会被前端渲染，拼接用户输入存在注入风险，
   * 且「用户 xxx 被锁定」会确认账号是否存在，抵消登录接口防枚举的设计。
   * 具体是谁被锁只写日志。
   * </p>
   */
  private static final String LOCKED_MESSAGE = "登录失败次数过多，请 5 分钟后再试";

  /** 账号维度计数键前缀。 */
  private static final String USER_KEY_PREFIX = "auth:loginFail:user:";

  /**
   * IP 维度计数键前缀。
   *
   * <p>
   * 与账号维度使用不同命名空间：用户名由用户自行填写，若共用前缀，
   * 注册一个形如 {@code ip:1.2.3.4} 的用户名就能污染对应 IP 的计数。
   * </p>
   */
  private static final String IP_KEY_PREFIX = "auth:loginFail:ip:";

  /** Redis 模板，用于读取与清除计数。 */
  private final StringRedisTemplate stringRedisTemplate;

  /** 原子计数器，提供「自增 + 首次设置 TTL」原语。 */
  private final RedisAtomicCounter redisAtomicCounter;

  /**
   * 构造限流器。
   *
   * @param stringRedisTemplate Redis 模板
   * @param redisAtomicCounter  原子计数器
   */
  public LoginRateLimiter(StringRedisTemplate stringRedisTemplate, RedisAtomicCounter redisAtomicCounter) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.redisAtomicCounter = redisAtomicCounter;
  }

  /**
   * 校验账号维度是否已被锁定。必须在查库之前调用，被锁定的请求不应再消耗数据库连接。
   *
   * @param username 登录名
   * @throws BusinessException 失败次数达到上限时抛出，状态码 429
   */
  public void checkUserLocked(String username) {
    assertNotLocked(USER_KEY_PREFIX + username, MAX_USER_FAIL_ATTEMPTS, "username=" + username);
  }

  /**
   * 校验 IP 维度是否已被锁定。必须在查库之前调用，用于阻断「换用户名撞库」。
   *
   * @param clientIp 客户端 IP，由 {@link ClientIpResolver} 解析，不能为空白
   * @throws BusinessException 失败次数达到上限时抛出，状态码 429
   */
  public void checkIpLocked(String clientIp) {
    assertNotLocked(IP_KEY_PREFIX + clientIp, MAX_IP_FAIL_ATTEMPTS, "ip=" + clientIp);
  }

  /**
   * 记录一次登录失败，同时累加账号与 IP 两个维度。
   *
   * <p>
   * 只记账、不判定：是否锁定由下一次请求的 {@link #checkUserLocked} / {@link #checkIpLocked} 决定。
   * 这样调用方不会因为「记账时抛异常」而把记录逻辑挪进成功分支。
   * </p>
   *
   * <p>
   * 注意：账号不存在时同样需要调用本方法，否则攻击者可以用不存在的用户名无限探测。
   * </p>
   *
   * @param username 登录名，允许是不存在的账号
   * @param clientIp 客户端 IP，不能为空白
   */
  public void recordFailure(String username, String clientIp) {
    Long userFailCount = redisAtomicCounter.incrWithTtl(USER_KEY_PREFIX + username, WINDOW_SECONDS);
    Long ipFailCount = redisAtomicCounter.incrWithTtl(IP_KEY_PREFIX + clientIp, WINDOW_SECONDS);
    log.warn("登录失败计数, username={}, userFailCount={}, ip={}, ipFailCount={}",
        username, userFailCount, clientIp, ipFailCount);
  }

  /**
   * 登录成功时清除账号维度的失败计数。
   *
   * <p>
   * 刻意不清 IP 维度：否则攻击者只要持有一个可用账号，就能通过「试错若干次 + 成功登录一次」
   * 不断重置 IP 配额，IP 维度限流形同虚设。IP 计数交给窗口自然过期。
   * </p>
   *
   * @param username 登录成功的登录名
   */
  public void clearOnSuccess(String username) {
    stringRedisTemplate.delete(USER_KEY_PREFIX + username);
  }

  /**
   * 判定指定计数键是否达到锁定阈值。
   *
   * @param key         计数键
   * @param maxAttempts 允许的最大失败次数，达到即锁定
   * @param subject     日志用的主体描述，形如 {@code username=zhanggong}，不会进入响应体
   * @throws BusinessException 达到阈值时抛出 429
   */
  private void assertNotLocked(String key, int maxAttempts, String subject) {
    String value = stringRedisTemplate.opsForValue().get(key);
    if (value == null) {
      return;
    }

    long count;
    try {
      count = Long.parseLong(value);
    } catch (NumberFormatException e) {
      log.warn("登录失败计数为脏数据，已清除, key={}", key, e);
      stringRedisTemplate.delete(key);
      return;
    }

    if (count >= maxAttempts) {
      log.warn("登录被限流, {}, count={}, max={}", subject, count, maxAttempts);
      throw new BusinessException(429, LOCKED_MESSAGE);
    }
  }
}
