package com.servicedesk.service;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.vo.AttachmentVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件服务，对应接口文档第 4 章 F-05（附件上传 / 删除）。
 *
 * <p>
 * 属于业务层，维护 {@code ticket_attachment} 表并调用 {@code FileStorageService}
 * 写入/删除 OSS 对象。三条约定：
 * </p>
 *
 * <ul>
 * <li>文件本体在 OSS，表里只存元数据；OSS 写入不参与 MySQL 事务，写库失败要补偿删除；</li>
 * <li>上传时间由数据库默认值填充，响应中的时间以插入后的实体为准；</li>
 * <li>删除权限为「上传人本人或管理员」，其他人统一 404，不泄露附件是否存在。</li>
 * </ul>
 */
public interface FileAttachmentService {

  /**
   * 上传附件。
   *
   * <p>
   * 事务边界：单表插入 + 一次 OSS 写入，不开启事务。幂等性：不幂等，
   * 同一文件重复上传会生成两条记录与两个对象；删除旧记录即可清理。
   * </p>
   *
   * @param file    上传的文件，不能为空
   * @param bizType 业务类型 {@code TICKET} / {@code KB}，缺省为 TICKET
   * @return 附件信息，含 OSS 公网 URL
   * @throws BusinessException 空文件或类型不支持抛 400；超过大小上限抛 413；存储不可用抛 503
   */
  AttachmentVO upload(MultipartFile file, String bizType);

  /**
   * 删除附件（先删 OSS 对象，再删数据库记录）。
   *
   * <p>
   * 顺序不可颠倒：对象删除幂等且可重试，若在删库之后失败就会留下无法再次删除的孤儿记录。
   * 事务边界：单表删除，不开启事务。
   * </p>
   *
   * @param id 附件 ID
   * @throws BusinessException 附件不存在或非上传人且非管理员抛 404；存储不可用抛 503
   */
  void delete(Long id);
}
