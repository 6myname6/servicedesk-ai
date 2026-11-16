package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 附件视图对象，对应接口文档附录 A.11。
 *
 * <p>
 * 属于接口出参层。只暴露展示所需字段，不含 {@code uploaderId} 与 {@code ticketId}，
 * 上传人是服务端权限判断依据，不随响应外泄。
 * </p>
 */
@Getter
@Builder
public class AttachmentVO {

  /** 附件 ID。 */
  private final Long id;

  /** 原始文件名，用于前端展示与下载命名。 */
  private final String fileName;

  /** OSS 公网访问 URL。 */
  private final String fileUrl;

  /** 文件大小（字节）。 */
  private final Long fileSize;

  /** MIME 类型。 */
  private final String mimeType;

  /** 上传时间，序列化为 {@code yyyy-MM-dd'T'HH:mm:ss}。 */
  private final LocalDateTime createdAt;
}
