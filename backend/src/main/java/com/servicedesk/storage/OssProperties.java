package com.servicedesk.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储（阿里云 OSS）配置，对应 {@code application.yml} 的 {@code storage.oss} 前缀。
 *
 * <p>
 * 属于存储层配置。通过启动类的 {@code @ConfigurationPropertiesScan} 注册。
 * 四项必填配置都允许为空：为空时应用仍能启动，只是上传/删除接口返回 503，
 * 避免本地没有 OSS 账号时整机不可用（集成测试也不需要真实 OSS）。
 * </p>
 *
 * @param endpoint        地域节点，例如 {@code oss-cn-hangzhou.aliyuncs.com}，可带协议前缀
 * @param bucket          存储桶名
 * @param accessKeyId     AccessKey ID，生产环境用环境变量注入，禁止写进仓库
 * @param accessKeySecret AccessKey Secret，只用于签名，不落日志
 * @param publicUrlPrefix 可选公网前缀（CDN / 自定义域名），为空时按
 *                        {@code https://{bucket}.{endpoint}/} 拼接
 */
@ConfigurationProperties(prefix = "storage.oss")
public record OssProperties(
    String endpoint,
    String bucket,
    String accessKeyId,
    String accessKeySecret,
    String publicUrlPrefix) {

  /**
   * 判断必填四项（endpoint / bucket / AK / SK）是否齐备。
   *
   * @return true 表示可以创建 OSS 客户端
   */
  public boolean configured() {
    return isNotBlank(endpoint) && isNotBlank(bucket)
        && isNotBlank(accessKeyId) && isNotBlank(accessKeySecret);
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
