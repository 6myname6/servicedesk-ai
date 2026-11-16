package com.servicedesk.storage;

import com.aliyun.oss.OSS;
import com.servicedesk.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OSS 客户端提供者的单元测试。
 *
 * <p>
 * 属于存储层测试。只验证两件事：配置缺失时 fail-fast 返回 503；
 * 配置齐备时复用同一个客户端实例（不重复建连接池）。创建客户端不发起网络请求。
 * </p>
 */
class OssClientProviderTest {

  /**
   * 配置全空时调用返回 503，而不是空指针。
   */
  @Test
  @DisplayName("配置缺失：获取客户端返回 503")
  void shouldRejectWhenNotConfigured() {
    OssClientProvider provider = new OssClientProvider(
        new OssProperties("", "", "", "", null));

    assertThatThrownBy(provider::getClient)
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(503);
          assertThat(ex.getMessage()).isEqualTo("文件存储服务未配置，请联系管理员");
        });
  }

  /**
   * 配置齐备时两次获取返回同一实例，关闭后不再持有。
   */
  @Test
  @DisplayName("配置齐备：复用同一客户端实例")
  void shouldReuseClientInstance() {
    OssClientProvider provider = new OssClientProvider(
        new OssProperties("oss-cn-hangzhou.aliyuncs.com", "servicedesk-ai", "ak", "sk", null));

    OSS first = provider.getClient();
    OSS second = provider.getClient();
    assertThat(first).isSameAs(second);

    provider.shutdown();
  }
}
