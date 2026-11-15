package com.servicedesk.security;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户状态缓存：为无状态的 JWT 补一个「账号是否被停用」的服务端开关。
 *
 * <p>
 * 属于鉴权基础设施层。JWT 一旦签发，在有效期内自证有效，服务端无从得知账号是否已被停用，
 * 因此在 Redis 中放一个停用标记，由 {@code AuthInterceptor} 在每次请求时校验，
 * 使「停用」在下一个请求就生效。
 * </p>
 *
 * <p>
 * 设计取舍：只存「停用」一种标记，key 不存在即视为正常。
 * 这样 Redis 丢数据时只会让被停用者短暂恢复访问（影响面小），而不会把所有用户锁死。
 * 标记的 TTL 取 token 最长存活时间（{@code jwt.expire-seconds}），
 * 到期时旧 token 本身也已失效，无需再拦。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusCache {

    /** 停用标记键前缀，与 token 黑名单使用不同命名空间以区分「这个人不行」与「这个 token 不行」。 */
    private static final String USER_DISABLED_KEY_PREFIX = "auth:user:disabled:";

    /** Redis 模板。 */
    private final StringRedisTemplate stringRedisTemplate;

    /** JWT 配置，提供 token 最长存活时间作为标记 TTL。 */
    private final JwtProperties jwtProperties;

    /**
     * 判断账号是否已被停用。
     *
     * <p>
     * 只读、不缓存、不降级：Redis 不可用时异常向上抛出，
     * 由调用方决定是拒绝请求还是放行，避免这里悄悄吞掉安全判定。
     * </p>
     *
     * @param userId 用户 ID
     * @return true 表示已停用，应拒绝该账号的请求
     */
    public boolean isDisabled(Long userId) {
        String key = USER_DISABLED_KEY_PREFIX + userId;
        return stringRedisTemplate.hasKey(key);
    }

    /**
     * 标记账号已停用，停用接口在数据库更新成功后调用。
     *
     * <p>
     * 幂等：重复调用结果一致；TTL 取 token 最长存活时间，重复标记会刷新该时长，
     * 因为「停用」是一项持续状态，延长截止时间符合语义。
     * </p>
     *
     * @param userId 用户 ID
     */
    public void markDisabled(Long userId) {
        String key = USER_DISABLED_KEY_PREFIX + userId;
        stringRedisTemplate.opsForValue().set(key, "1", jwtProperties.expireSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 清除停用标记，账号重新启用后调用。
     *
     * <p>
     * 幂等：key 不存在时删除同样视为成功，重复启用不会报错。
     * </p>
     *
     * @param userId 用户 ID
     */
    public void clearDisabled(Long userId) {
        String key = USER_DISABLED_KEY_PREFIX + userId;
        stringRedisTemplate.delete(key);
    }
}
