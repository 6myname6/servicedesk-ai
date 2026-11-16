package com.servicedesk.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.servicedesk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 阿里云 OSS 文件存储实现。
 *
 * <p>
 * 属于存储层实现。对象键规则见接口文档「文件上传」约定：
 * {@code {bizType}/{yyyyMM}/{uuid}.{ext}}，其中 {@code yyyyMM} 取业务时区的当前月
 * （注入 {@link Clock}，便于单测固定时间）。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>文件名只取扩展名参与拼装，用户输入的文件名不会进入对象键，避免路径穿越；</li>
 * <li>OSS 异常统一转 503，原始错误码只写日志，不暴露给调用方；</li>
 * <li>删除按 URL 反解对象键，因此 URL 前缀必须与当前配置一致，否则返回 400。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssFileStorageService implements FileStorageService {

  /** 对象键中的月份格式，例如 202610。 */
  private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

  /** 允许的扩展名格式，防止扩展名里带路径或空格。 */
  private static final Pattern EXTENSION_PATTERN = Pattern.compile("[a-z0-9]{1,10}");

  /** 客户端未提供 MIME 时的兜底值。 */
  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  /** OSS 客户端提供者，延迟创建，便于测试替换。 */
  private final OssClientProvider clientProvider;

  /** OSS 配置，提供桶名与公网前缀。 */
  private final OssProperties properties;

  /** 业务时钟，决定对象键中的月份，禁止使用系统默认时区。 */
  private final Clock clock;

  /**
   * 上传文件到 OSS。
   *
   * @param bizType     业务目录，例如 {@code ticket} / {@code kb}
   * @param fileName    原始文件名，用于提取扩展名
   * @param contentType MIME 类型，为空时按 application/octet-stream
   * @param content     文件字节
   * @return 上传结果，含对象键与公网 URL
   * @throws BusinessException 文件名缺扩展名或 OSS 调用失败时抛出（400 / 503）
   */
  @Override
  public StoredFile upload(String bizType, String fileName, String contentType, byte[] content) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("文件内容不能为空");
    }
    String objectKey = buildObjectKey(bizType, fileName);
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(content.length);
    metadata.setContentType(isNotBlank(contentType) ? contentType : DEFAULT_CONTENT_TYPE);

    try {
      clientProvider.getClient().putObject(
          properties.bucket(), objectKey, new ByteArrayInputStream(content), metadata);
    } catch (OSSException e) {
      log.error("上传 OSS 失败, bucket={}, objectKey={}, errorCode={}",
          properties.bucket(), objectKey, e.getErrorCode(), e);
      throw new BusinessException(503, "文件存储服务不可用，请稍后重试");
    } catch (ClientException e) {
      log.error("上传 OSS 失败（客户端异常）, bucket={}, objectKey={}", properties.bucket(), objectKey, e);
      throw new BusinessException(503, "文件存储服务不可用，请稍后重试");
    }

    String url = urlOf(objectKey);
    log.info("上传 OSS 成功, bucket={}, objectKey={}, size={}", properties.bucket(), objectKey, content.length);
    return new StoredFile(objectKey, url);
  }

  /**
   * 按公网 URL 删除 OSS 对象。
   *
   * <p>
   * 先按当前配置拼出前缀再反解对象键；OSS 的 deleteObject 对不存在的对象不报错，
   * 因此本方法天然幂等，调用方失败重试不会产生副作用。
   * </p>
   *
   * @param fileUrl 上传时返回的公网 URL
   * @throws BusinessException URL 不属于当前存储时抛出 400；OSS 调用失败时抛出 503
   */
  @Override
  public void deleteByUrl(String fileUrl) {
    String prefix = urlPrefix();
    String objectKey = stripPrefix(fileUrl, prefix);
    if (objectKey.isEmpty()) {
      log.warn("删除 OSS 对象失败：URL 不合法, prefix={}", prefix);
      throw new BusinessException(400, "文件地址不合法");
    }
    try {
      clientProvider.getClient().deleteObject(properties.bucket(), objectKey);
    } catch (OSSException e) {
      log.error("删除 OSS 对象失败, bucket={}, objectKey={}, errorCode={}",
          properties.bucket(), objectKey, e.getErrorCode(), e);
      throw new BusinessException(503, "文件存储服务不可用，请稍后重试");
    } catch (ClientException e) {
      log.error("删除 OSS 对象失败（客户端异常）, bucket={}, objectKey={}", properties.bucket(), objectKey, e);
      throw new BusinessException(503, "文件存储服务不可用，请稍后重试");
    }
    log.info("删除 OSS 对象成功, bucket={}, objectKey={}", properties.bucket(), objectKey);
  }

  /**
   * 生成对象键：{@code {bizType}/{yyyyMM}/{uuid}.{ext}}。
   *
   * @param bizType  业务目录
   * @param fileName 原始文件名
   * @return 对象键
   * @throws BusinessException 文件名没有合法扩展名时抛出 400
   */
  private String buildObjectKey(String bizType, String fileName) {
    String extension = extensionOf(fileName);
    String month = YearMonth.now(clock).format(MONTH_FORMATTER);
    String bizDir = bizType == null ? "other" : bizType.trim().toLowerCase(Locale.ROOT);
    String uuid = UUID.randomUUID().toString().replace("-", "");
    return bizDir + "/" + month + "/" + uuid + "." + extension;
  }

  /**
   * 从文件名提取小写扩展名。
   *
   * @param fileName 原始文件名
   * @return 扩展名，不含点
   * @throws BusinessException 缺少扩展名或扩展名格式非法时抛出 400
   */
  private String extensionOf(String fileName) {
    if (fileName == null) {
      log.warn("上传文件缺少文件名");
      throw new BusinessException(400, "文件名缺少扩展名");
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      log.warn("上传文件缺少扩展名, fileName={}", fileName);
      throw new BusinessException(400, "文件名缺少扩展名");
    }
    String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (!EXTENSION_PATTERN.matcher(extension).matches()) {
      log.warn("上传文件扩展名非法, fileName={}", fileName);
      throw new BusinessException(400, "文件名缺少扩展名");
    }
    return extension;
  }

  /**
   * 由对象键拼出公网 URL。
   *
   * @param objectKey 对象键
   * @return 公网 URL
   */
  private String urlOf(String objectKey) {
    return urlPrefix() + objectKey;
  }

  /**
   * 计算公网 URL 前缀，固定以 {@code /} 结尾。
   *
   * <p>
   * 配了 {@code public-url-prefix} 时直接用它（CDN / 自定义域名）；
   * 否则按 {@code {scheme}://{bucket}.{endpoint}/} 拼装。
   * </p>
   *
   * @return 形如 {@code https://bucket.oss-cn-hangzhou.aliyuncs.com/} 的前缀
   * @throws BusinessException 配置缺失时抛出 503
   */
  private String urlPrefix() {
    String configuredPrefix = trimToNull(properties.publicUrlPrefix());
    if (configuredPrefix != null) {
      return stripTrailingSlash(configuredPrefix) + "/";
    }
    String endpoint = trimToNull(properties.endpoint());
    if (endpoint == null || !isNotBlank(properties.bucket())) {
      log.error("对象存储未配置，无法生成文件 URL");
      throw new BusinessException(503, "文件存储服务未配置，请联系管理员");
    }
    String scheme = endpoint.startsWith("http://") ? "http" : "https";
    String host = endpoint.replaceFirst("^https?://", "");
    return scheme + "://" + properties.bucket() + "." + stripTrailingSlash(host) + "/";
  }

  /**
   * 从公网 URL 中截出对象键。
   *
   * @param fileUrl 公网 URL
   * @param prefix  当前存储的 URL 前缀
   * @return 对象键，URL 不属于当前存储时返回空串
   */
  private String stripPrefix(String fileUrl, String prefix) {
    if (fileUrl == null || !fileUrl.startsWith(prefix)) {
      return "";
    }
    return fileUrl.substring(prefix.length());
  }

  /**
   * 去掉末尾的斜杠。
   *
   * @param value 原始字符串
   * @return 去尾斜杠后的值
   */
  private String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  /**
   * 去掉首尾空白，空串归一为 null。
   *
   * @param value 原始字符串
   * @return 去空白后的值，空白时返回 null
   */
  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * 判断字符串非空白。
   *
   * @param value 待判断字符串
   * @return true 表示存在非空白内容
   */
  private boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }
}
