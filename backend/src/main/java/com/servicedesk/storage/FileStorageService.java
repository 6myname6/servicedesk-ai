package com.servicedesk.storage;

import com.servicedesk.common.exception.BusinessException;

/**
 * 文件存储抽象：屏蔽具体存储介质，业务层只依赖本接口。
 *
 * <p>
 * 属于存储层。本期实现为阿里云 OSS（{@link OssFileStorageService}）；
 * 若后续换 MinIO / S3，只需新增实现类，表结构与接口契约不变。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>{@link #upload} 不幂等：同一文件重复上传会生成不同对象键；</li>
 * <li>{@link #deleteByUrl} 幂等：对象不存在也视为成功，便于失败重试；</li>
 * <li>存储写入不参与 MySQL 事务，调用方需在写库失败后补偿删除已上传对象。</li>
 * </ul>
 */
public interface FileStorageService {

  /**
   * 上传文件并返回可访问 URL。
   *
   * <p>
   * 对象键由实现按 {@code {bizType}/{yyyyMM}/{uuid}.{ext}} 生成，
   * 文件名只取扩展名参与拼装，不直接使用用户输入的文件名。
   * </p>
   *
   * @param bizType     业务目录，小写，例如 {@code ticket} / {@code kb}
   * @param fileName    原始文件名，用于提取扩展名
   * @param contentType MIME 类型，为空时按 application/octet-stream 处理
   * @param content     文件字节
   * @return 上传结果，含对象键与公网 URL
   * @throws BusinessException 存储未配置或调用失败时抛出 503
   */
  StoredFile upload(String bizType, String fileName, String contentType, byte[] content);

  /**
   * 按公网 URL 删除对象。
   *
   * <p>
   * 幂等：对象已不存在时同样成功，调用方可以安全重试。
   * </p>
   *
   * @param fileUrl 上传时返回的公网 URL
   * @throws BusinessException URL 不属于当前存储时抛出 400；存储未配置或调用失败时抛出 503
   */
  void deleteByUrl(String fileUrl);
}
