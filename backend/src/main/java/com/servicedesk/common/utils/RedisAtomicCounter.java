package com.servicedesk.common.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis 的原子计数器（通用基础设施层，不承载业务语义）。
 *
 * <p>
 * 只提供「自增 + 首次设置 TTL」这一种原语，用于固定时间窗口内的计数场景，
 * 例如登录失败次数、窗口内调用次数、按日流水号。窗口长度由调用方决定，
 * 本类不感知业务，也不负责 key 命名空间，调用方需自带业务前缀避免 key 冲突。
 * </p>
 *
 * <p>
 * 计数与过期必须原子完成，因此统一走 Lua 脚本执行，禁止拆成多次 Redis 调用。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class RedisAtomicCounter {
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 自增并在窗口创建时设置过期时间的 Lua 脚本。
     *
     * <p>
     * 仅在 {@code count == 1} 时执行 EXPIRE，保证 TTL 只随窗口创建设置一次、
     * 后续自增不续期，从而构成固定窗口；若每次自增都续期，活跃 key 将永不过期，
     * 计数无法按周期重置。
     * </p>
     */
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<> (
            "local count = redis.call('INCR', KEYS[1])\n" + //
                    "if count == 1 then\n" + //
                    "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n" + //
                    "end\n" + //
                    "return count",
            Long.class
    );

    /**
     * 对指定 key 原子自增 1，并在该 key 首次创建时设置过期时间。
     *
     * <p>
     * 幂等性：本方法不幂等，每次调用都会使计数加 1。
     * 事务边界：单条 Lua 脚本在 Redis 端原子执行，不参与 Spring 数据库事务；
     * 数据库回滚不会回退已完成的计数，需要与业务结果严格一致时由调用方自行补偿。
     * </p>
     *
     * @param key       计数键，调用方需带业务前缀，如 {@code counter:login:fail:1001}
     * @param ttlSeconds 窗口长度（秒），仅在 key 首次创建时生效，必须大于 0
     * @return 自增后的当前计数值，窗口内首次调用返回 1
     * @throws org.springframework.data.redis.RedisConnectionFailureException Redis 不可用时抛出，
     *         该链路若不允失败，调用方需自行降级
     */
    public long incrWithTtl(String key, long ttlSeconds)
    {
        return stringRedisTemplate.execute(INCREMENT_SCRIPT, Collections.singletonList(key), String.valueOf(ttlSeconds));
    }

}
