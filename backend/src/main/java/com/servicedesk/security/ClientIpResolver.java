package com.servicedesk.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 客户端真实 IP 解析器。
 *
 * <p>
 * 属于鉴权基础设施层，供登录频控使用。解析策略只有两种，由
 * {@link ClientIpProperties#trustProxy()} 决定：
 * </p>
 *
 * <ul>
 * <li>默认（{@code trust-proxy=false}）：直接取 {@code request.getRemoteAddr()}。
 * 请求头可以被请求方任意伪造，直连部署时只有 TCP 连接的真实来源可信。</li>
 * <li>开启（{@code trust-proxy=true}）：取 {@code X-Forwarded-For} 的第一个地址，
 * 即最靠近客户端的那个。只有确认前置代理会覆写该请求头时才可开启。</li>
 * </ul>
 *
 * <p>
 * 返回值保证非空白：拿不到地址时统一返回 {@code unknown}，让这些请求共用一个计数桶，
 * 而不是让限流因为「没有 IP」而整体失效。
 * </p>
 */
@Component
public class ClientIpResolver {

  /** 反向代理链路中记录客户端地址的请求头。 */
  private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

  /** 拿不到地址时的占位值，避免返回 null 让调用方到处判空。 */
  private static final String UNKNOWN_IP = "unknown";

  /** 请求头中可能出现的无意义取值，大小写不敏感。 */
  private static final String HEADER_VALUE_UNKNOWN = "unknown";

  /**
   * 计数键中 IP 的最大长度。
   *
   * <p>
   * 防止伪造的超长请求头被当作 Redis key 写入，同时避免键名撑大内存。
   * IPv6 最长 45 个字符，64 足够覆盖。
   * </p>
   */
  private static final int MAX_IP_LENGTH = 64;

  /** 是否信任代理传递的客户端地址。 */
  private final boolean trustProxy;

  /**
   * 构造解析器。
   *
   * @param properties 客户端 IP 配置，不能为 null
   */
  public ClientIpResolver(ClientIpProperties properties) {
    this.trustProxy = properties.trustProxy();
  }

  /**
   * 解析当前请求的客户端 IP。
   *
   * @param request 当前 HTTP 请求，不能为 null
   * @return 非空白的客户端 IP；无法解析时返回 {@code unknown}
   */
  public String resolve(HttpServletRequest request) {
    if (trustProxy) {
      String forwarded = firstForwardedAddress(request);
      if (forwarded != null) {
        return forwarded;
      }
    }
    return normalize(request.getRemoteAddr());
  }

  /**
   * 从 {@code X-Forwarded-For} 中取出最靠近客户端的地址。
   *
   * <p>
   * 该请求头的格式是 {@code 客户端 IP, 代理1, 代理2}，每经过一跳追加一个地址，
   * 因此第一个才是真实客户端；后续地址由代理填写，位置越靠后越可信。
   * </p>
   *
   * @param request 当前 HTTP 请求
   * @return 规范化后的地址；请求头缺失、为空或为 {@code unknown} 时返回 null
   */
  private String firstForwardedAddress(HttpServletRequest request) {
    String header = request.getHeader(HEADER_X_FORWARDED_FOR);
    if (header == null || header.isBlank()) {
      return null;
    }
    int commaIndex = header.indexOf(',');
    String first = commaIndex > 0 ? header.substring(0, commaIndex) : header;
    String normalized = normalize(first);
    return UNKNOWN_IP.equals(normalized) ? null : normalized;
  }

  /**
   * 去掉首尾空白、限制长度，并把空值统一成占位值。
   *
   * @param raw 原始地址，可能为 null 或空白
   * @return 可直接作为计数键组成部分的地址
   */
  private String normalize(String raw) {
    if (raw == null) {
      return UNKNOWN_IP;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty() || HEADER_VALUE_UNKNOWN.equalsIgnoreCase(trimmed)) {
      return UNKNOWN_IP;
    }
    return trimmed.length() > MAX_IP_LENGTH ? trimmed.substring(0, MAX_IP_LENGTH) : trimmed;
  }
}
