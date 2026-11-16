package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单附件实体，对应表 {@code ticket_attachment}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.7 一一对应，{@code ticket_id} 与
 * {@code uploader_id} 均为逻辑外键，不建物理约束。
 * </p>
 *
 * <p>
 * 只存元数据与 OSS 公网 URL，不存文件本体；{@code ticket_id} 允许为 null，
 * 表示上传完成但尚未建单（建单时按 {@code imageUrls} 回填）。
 * </p>
 */
@Data
@TableName("ticket_attachment")
public class TicketAttachment {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 工单 ID，逻辑外键 ticket.id；上传后未关联工单时为 null。 */
  private Long ticketId;

  /** 上传人 ID，逻辑外键 sys_user.id。 */
  private Long uploaderId;

  /** 原始文件名（不含客户端路径），仅用于展示与下载命名。 */
  private String fileName;

  /** 公网访问 URL，由 {@code FileStorageService} 生成。 */
  private String fileUrl;

  /** 文件大小（字节）。 */
  private Long fileSize;

  /** MIME 类型，客户端未提供时为 application/octet-stream。 */
  private String mimeType;

  /** 上传时间，数据库默认值填充。 */
  private LocalDateTime createdAt;
}
