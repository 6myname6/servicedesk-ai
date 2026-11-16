package com.servicedesk.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.servicedesk.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OSS 存储实现的单元测试。
 *
 * <p>
 * 属于存储层测试。OSS 客户端整体 mock，不触网；时钟固定为 2026-10-27 09:00（东八区），
 * 断言对象键里的月份为 {@code 202610}，避免用例跨月失效。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class OssFileStorageServiceTest {

  /** 固定时钟：2026-10-27 09:00:00（Asia/Shanghai）。 */
  private static final Clock FIXED_CLOCK = Clock.fixed(
      Instant.parse("2026-10-27T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

  /** 测试桶名。 */
  private static final String BUCKET = "servicedesk-ai";

  /** 测试地域节点。 */
  private static final String ENDPOINT = "oss-cn-hangzhou.aliyuncs.com";

  /** OSS 客户端替身。 */
  @Mock
  private OSS oss;

  /** 客户端提供者替身。 */
  @Mock
  private OssClientProvider clientProvider;

  /** 被测实现，每个用例按不同配置重建。 */
  private OssFileStorageService storageService;

  /** 当前用例的配置。 */
  private OssProperties properties;

  /**
   * 准备默认配置（无 CDN 前缀）。
   */
  @BeforeEach
  void setUp() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    properties = new OssProperties(ENDPOINT, BUCKET, "ak", "sk", null);
    storageService = new OssFileStorageService(clientProvider, properties, FIXED_CLOCK);
  }

  /**
   * 上传成功：对象键按 {bizType}/{yyyyMM}/{uuid}.{ext} 生成，公网 URL 与元数据正确。
   */
  @Test
  @DisplayName("上传：对象键含月份与 UUID，URL 按桶域名拼接")
  void shouldUploadWithGeneratedObjectKey() {
    when(clientProvider.getClient()).thenReturn(oss);
    byte[] content = "hello".getBytes();

    StoredFile stored = storageService.upload("ticket", "photo.JPG", "image/jpeg", content);

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ObjectMetadata> metadataCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
    verify(oss).putObject(eq(BUCKET), keyCaptor.capture(),
        any(InputStream.class), metadataCaptor.capture());

    String objectKey = keyCaptor.getValue();
    assertThat(objectKey).matches("ticket/202610/[0-9a-f]{32}\\.jpg");
    assertThat(stored.objectKey()).isEqualTo(objectKey);
    assertThat(stored.url()).isEqualTo("https://" + BUCKET + "." + ENDPOINT + "/" + objectKey);
    assertThat(metadataCaptor.getValue().getContentType()).isEqualTo("image/jpeg");
    assertThat(metadataCaptor.getValue().getContentLength()).isEqualTo(content.length);
  }

  /**
   * 配了自定义域名时，URL 使用自定义前缀。
   */
  @Test
  @DisplayName("上传：配置 public-url-prefix 时使用自定义域名")
  void shouldUsePublicUrlPrefix() {
    OssProperties cdnProperties = new OssProperties(ENDPOINT, BUCKET, "ak", "sk",
        "https://cdn.example.com/");
    storageService = new OssFileStorageService(clientProvider, cdnProperties, FIXED_CLOCK);
    when(clientProvider.getClient()).thenReturn(oss);

    StoredFile stored = storageService.upload("kb", "guide.pdf", "application/pdf", new byte[] { 1 });

    assertThat(stored.url()).startsWith("https://cdn.example.com/kb/202610/").endsWith(".pdf");
  }

  /**
   * 删除按 URL 反解对象键，调用 OSS 删除。
   */
  @Test
  @DisplayName("删除：按 URL 反解对象键并调用 deleteObject")
  void shouldDeleteByUrl() {
    when(clientProvider.getClient()).thenReturn(oss);
    String url = "https://" + BUCKET + "." + ENDPOINT + "/ticket/202610/abc.jpg";

    storageService.deleteByUrl(url);

    verify(oss).deleteObject(BUCKET, "ticket/202610/abc.jpg");
  }

  /**
   * URL 不属于当前存储（例如历史本地地址）时返回 400，避免误删其他桶的对象。
   */
  @Test
  @DisplayName("删除：URL 不属于当前存储返回 400")
  void shouldRejectForeignUrl() {
    assertThatThrownBy(() -> storageService.deleteByUrl("http://localhost:8080/storage/ticket/a.jpg"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
    verifyNoInteractions(clientProvider);
  }

  /**
   * OSS 客户端异常转 503，不把 SDK 细节抛给调用方。
   */
  @Test
  @DisplayName("上传：OSS 客户端异常返回 503")
  void shouldMapOssFailureTo503() {
    when(clientProvider.getClient()).thenReturn(oss);
    doThrow(new ClientException("network unreachable"))
        .when(oss).putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class));

    assertThatThrownBy(() -> storageService.upload("ticket", "a.jpg", "image/jpeg", new byte[] { 1 }))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(503);
          assertThat(ex.getMessage()).isEqualTo("文件存储服务不可用，请稍后重试");
        });
  }

  /**
   * 文件名没有扩展名时返回 400，不生成无后缀对象。
   */
  @Test
  @DisplayName("上传：文件名缺少扩展名返回 400")
  void shouldRejectFileWithoutExtension() {
    assertThatThrownBy(() -> storageService.upload("ticket", "photo", "image/jpeg", new byte[] { 1 }))
        .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
    verifyNoInteractions(clientProvider);
  }
}
