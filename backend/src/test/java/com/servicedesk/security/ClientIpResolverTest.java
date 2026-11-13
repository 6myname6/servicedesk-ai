package com.servicedesk.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析器的单元测试。
 *
 * <p>
 * 属于鉴权基础设施层测试，不启动 Spring 容器。覆盖两件事：
 * 默认配置下必须忽略可伪造的转发头；开启信任代理后必须取到最靠近客户端的那一段地址。
 * </p>
 */
class ClientIpResolverTest {

  /** TCP 连接的真实来源地址，模拟直连场景。 */
  private static final String REMOTE_ADDR = "192.0.2.10";

  /** 转发头中伪造/传递的客户端地址。 */
  private static final String FORWARDED_IP = "203.0.113.7";

  /** 计数键允许的最大地址长度，与实现保持一致。 */
  private static final int MAX_IP_LENGTH = 64;

  /**
   * 默认配置（不信任代理）下，必须忽略 X-Forwarded-For。
   *
   * <p>
   * 这是安全底线：直连部署时请求头由客户端完全控制，
   * 一旦采信，攻击者每次换一个假 IP 即可绕过 IP 维度限流。
   * </p>
   */
  @Test
  @DisplayName("不信任代理：忽略 X-Forwarded-For，取连接地址")
  void shouldIgnoreForwardedHeaderWhenProxyNotTrusted() {
    ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties(false));
    MockHttpServletRequest request = buildRequest();
    request.addHeader("X-Forwarded-For", FORWARDED_IP);

    assertThat(resolver.resolve(request)).isEqualTo(REMOTE_ADDR);
  }

  /**
   * 信任代理时取第一段地址：该头格式为「客户端, 代理1, 代理2」，越靠前越接近客户端。
   */
  @Test
  @DisplayName("信任代理：取 X-Forwarded-For 第一段并去除空白")
  void shouldUseFirstForwardedAddressWhenProxyTrusted() {
    ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties(true));
    MockHttpServletRequest request = buildRequest();
    request.addHeader("X-Forwarded-For", "  " + FORWARDED_IP + " , 10.0.0.1 , 10.0.0.2");

    assertThat(resolver.resolve(request)).isEqualTo(FORWARDED_IP);
  }

  /**
   * 信任代理但请求头缺失时必须回退，不能返回 null 让限流静默失效。
   */
  @Test
  @DisplayName("信任代理但无转发头：回退到连接地址")
  void shouldFallBackToRemoteAddrWhenHeaderMissing() {
    ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties(true));

    assertThat(resolver.resolve(buildRequest())).isEqualTo(REMOTE_ADDR);
  }

  /**
   * 部分代理会写入字面量 unknown，这种值不可作为计数键。
   */
  @Test
  @DisplayName("转发头为 unknown：回退到连接地址")
  void shouldFallBackWhenForwardedHeaderIsUnknown() {
    ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties(true));
    MockHttpServletRequest request = buildRequest();
    request.addHeader("X-Forwarded-For", "unknown");

    assertThat(resolver.resolve(request)).isEqualTo(REMOTE_ADDR);
  }

  /**
   * 超长地址必须截断，避免伪造的超长请求头被当作 Redis 键写入。
   */
  @Test
  @DisplayName("超长地址：截断到 64 位")
  void shouldTruncateOverlongAddress() {
    ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties(false));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("1".repeat(200));

    assertThat(resolver.resolve(request)).hasSize(MAX_IP_LENGTH);
  }

  /**
   * 连接地址为空时返回占位值，让这些请求共用一个计数桶，而不是整条限流链路失效。
   */
  @Test
  @DisplayName("连接地址为空白：返回 unknown 占位值")
  void shouldReturnUnknownWhenRemoteAddrBlank() {
    ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties(false));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("   ");

    assertThat(resolver.resolve(request)).isEqualTo("unknown");
  }

  /**
   * 构造携带固定连接地址的请求。
   *
   * @return 已设置 remoteAddr 的模拟请求
   */
  private MockHttpServletRequest buildRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(REMOTE_ADDR);
    return request;
  }
}
