package com.servicedesk.security;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与解析工具。
 *
 * <p>
 * 属于鉴权基础设施层。载荷只写鉴权必需字段（用户 ID、登录名、显示名、角色、团队），
 * 不放密码等敏感数据；前端传的角色与用户 ID 一律不信任，全部以本类解析结果为准。
 * </p>
 *
 * <p>
 * 解析失败统一抛 401，不向上区分「过期」与「伪造」，避免给攻击者反馈。
 * </p>
 */
@Component
public class JwtUtil {

  /** 载荷字段名：登录名。 */
  private static final String CLAIM_USERNAME = "username";

  /** 载荷字段名：显示名。 */
  private static final String CLAIM_DISPLAY_NAME = "displayName";

  /** 载荷字段名：角色。 */
  private static final String CLAIM_ROLE = "role";

  /** 载荷字段名：团队 ID。 */
  private static final String CLAIM_TEAM_ID = "teamId";

  /** HS256 要求的最小密钥长度（字节），不足时 jjwt 会拒绝签名。 */
  private static final int MIN_SECRET_BYTES = 32;

  /** 签名密钥，由配置中的 secret 派生。 */
  private final SecretKey secretKey;

  /** JWT 配置，提供有效期。 */
  private final JwtProperties properties;

  /**
   * 构造工具并校验密钥强度。
   *
   * @param properties JWT 配置
   * @throws IllegalStateException 密钥长度不足 32 字节时抛出，属于启动期配置错误
   */
  public JwtUtil(JwtProperties properties) {
    this.properties = properties;
    try {
      this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    } catch (WeakKeyException | IllegalArgumentException e) {
      throw new IllegalStateException("jwt.secret 长度不足 " + MIN_SECRET_BYTES + " 字节，无法用于 HS256 签名", e);
    }
  }

  /**
   * 为登录用户签发 token。
   *
   * @param user 登录用户快照，不能为 null
   * @return 签好名的 JWT 字符串
   */
  public String generate(LoginUser user) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(String.valueOf(user.id()))
        .claim(CLAIM_USERNAME, user.username())
        .claim(CLAIM_DISPLAY_NAME, user.displayName())
        .claim(CLAIM_ROLE, user.role())
        .claim(CLAIM_TEAM_ID, user.teamId())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(properties.expireSeconds())))
        .signWith(secretKey)
        .compact();
  }

  /**
   * 解析 token 并还原登录用户。
   *
   * @param token 前端携带的 JWT，不含 Bearer 前缀
   * @return 登录用户快照
   * @throws BusinessException token 过期、被篡改或格式非法时抛出，状态码 401
   */
  public LoginUser parse(String token) {
    Claims claims;
    try {
      claims = parseClaims(token);
    } catch (ExpiredJwtException e) {
      throw new BusinessException(401, "登录已过期，请重新登录");
    } catch (JwtException | IllegalArgumentException e) {
      throw new BusinessException(401, "登录凭证无效");
    }

    try {
      return new LoginUser(
          Long.valueOf(claims.getSubject()),
          claims.get(CLAIM_USERNAME, String.class),
          claims.get(CLAIM_DISPLAY_NAME, String.class),
          claims.get(CLAIM_ROLE, String.class),
          claims.get(CLAIM_TEAM_ID) == null ? null : ((Number) claims.get(CLAIM_TEAM_ID)).longValue());
    } catch (RuntimeException e) {
      throw new BusinessException(401, "登录凭证无效");
    }
  }

  /**
   * 计算 token 剩余有效秒数，用于登出时设置黑名单的过期时间。
   *
   * @param token 前端携带的 JWT
   * @return 剩余秒数，已过期或无法解析时返回 0
   */
  public long remainingSeconds(String token) {
    try {
      Instant expiration = parseClaims(token).getExpiration().toInstant();
      return Math.max(expiration.getEpochSecond() - Instant.now().getEpochSecond(), 0);
    } catch (JwtException | IllegalArgumentException e) {
      return 0;
    }
  }

  /**
   * token 有效期（秒），供登录接口回填 {@code expiresIn}。
   *
   * @return 配置的有效期秒数
   */
  public long getExpireSeconds() {
    return properties.expireSeconds();
  }

  /**
   * 校验签名并取出载荷。
   *
   * @param token JWT 字符串
   * @return 载荷
   */
  public Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(secretKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }
}
