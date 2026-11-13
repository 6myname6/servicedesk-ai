package com.servicedesk.security;

import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 登出黑名单
 * TokenBlackList
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TokenBlackList {
    // key前缀
    private static final String TOKEN_BLACKLIST_KEY_PREFIX = "auth:blacklist:";
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtil jwtUtil;

    /* 私有方法 */

    /**
     * 算哈希指纹
     */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.warn("不支持算法SHA-256");
            throw new RuntimeException("不支持算法SHA-256", e);
        }
    }

    /**
     * 添加黑名单
     * 
     * @param token
     */
    public void addBlackList(String token) {
        Claims claims = jwtUtil.parseClaims(token);
        Date expiration = claims.getExpiration();
        long ttlMs = expiration.getTime() - System.currentTimeMillis();
        if (ttlMs <= 0) {
            ttlMs = 1000L;
        }
        String key = TOKEN_BLACKLIST_KEY_PREFIX + sha256Hex(token);
        stringRedisTemplate.opsForValue().set(key, "1", ttlMs, TimeUnit.MILLISECONDS);
        log.info("剩余秒数：{}", ttlMs / 1000);
    }

    /**
     * 检查是否在黑名单中
     * 
     * @param token
     */
    public boolean isInBlackList(String token) {
        try {
            String key = TOKEN_BLACKLIST_KEY_PREFIX + sha256Hex(token);
            return stringRedisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("检查黑名单失败", e);
            return false;
        }
    }
}
