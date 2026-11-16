package com.servicedesk.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.servicedesk.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OSS 客户端提供者：首次使用时才创建并缓存 {@link OSS} 实例。
 *
 * <p>
 * 属于存储层基础设施。延迟创建有两个原因：一是 OSS 配置为空时应用仍能启动，
 * 只有真正上传/删除时才报 503；二是单元测试可以整体 mock 本类，不触网。
 * </p>
 *
 * <p>
 * 关键约束：客户端内部持有连接池，全应用共用一个实例，禁止每次调用新建；
 * 容器关闭时由 {@link #shutdown()} 释放。日志只记录桶名与节点，不记录 AK/SK。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssClientProvider {

  /** OSS 配置。 */
  private final OssProperties properties;

  /** 双检锁对象，保证并发首次调用只创建一个客户端。 */
  private final Object lock = new Object();

  /** 缓存的 OSS 客户端，未初始化时为 null。 */
  private volatile OSS client;

  /**
   * 获取 OSS 客户端，首次调用时创建。
   *
   * @return 可用的 OSS 客户端
   * @throws BusinessException 配置缺失时抛出 503
   */
  public OSS getClient() {
    if (!properties.configured()) {
      log.error("对象存储未配置，endpoint/bucket/accessKey 缺失，拒绝访问 OSS");
      throw new BusinessException(503, "文件存储服务未配置，请联系管理员");
    }
    OSS current = client;
    if (current == null) {
      synchronized (lock) {
        current = client;
        if (current == null) {
          current = new OSSClientBuilder().build(
              properties.endpoint(), properties.accessKeyId(), properties.accessKeySecret());
          client = current;
          log.info("OSS 客户端初始化完成, endpoint={}, bucket={}", properties.endpoint(), properties.bucket());
        }
      }
    }
    return current;
  }

  /**
   * 容器关闭时释放 OSS 客户端连接池。
   *
   * <p>
   * 未创建过客户端时什么都不做，避免空指针。
   * </p>
   */
  @PreDestroy
  public void shutdown() {
    OSS current = client;
    if (current != null) {
      current.shutdown();
      client = null;
      log.info("OSS 客户端已释放, bucket={}", properties.bucket());
    }
  }
}
