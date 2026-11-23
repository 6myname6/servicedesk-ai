package com.servicedesk.service.impl;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.enums.FileBizType;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.TicketAttachmentMapper;
import com.servicedesk.service.FileAttachmentService;
import com.servicedesk.service.support.TicketAttachEventWriter;
import com.servicedesk.storage.FileStorageService;
import com.servicedesk.storage.StoredFile;
import com.servicedesk.vo.AttachmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

/**
 * 附件服务实现。
 *
 * <p>
 * 属于业务实现层。上传链路：校验 → 写 OSS → 写库；写库失败时补偿删除 OSS 对象，
 * 避免产生「文件在、记录不在」的孤儿文件。删除链路：先写 ATTACH 事件（仅已关联工单），
 * 再删 OSS 对象与库记录，三步同一事务；对象删除幂等，失败可重试。
 * </p>
 *
 * <p>
 * 日志约定：成功记 info（附件 ID、业务类型、大小、上传人），拒绝记 warn（原因 + 操作人），
 * 存储/写库故障记 error；文件名与 URL 不作为敏感信息，但仍不记录 AccessKey 等凭据。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAttachmentServiceImpl implements FileAttachmentService {

    /** 客户端未提供文件名时的兜底展示名。 */
    private static final String DEFAULT_FILE_NAME = "file";

    /** 客户端未提供 MIME 时的兜底值，与 OSS 实现保持一致。 */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** ticket_attachment.file_name 的字段长度上限。 */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /** ticket_attachment.mime_type 的字段长度上限。 */
    private static final int MAX_MIME_TYPE_LENGTH = 64;

    /** 附件 Mapper。 */
    private final TicketAttachmentMapper attachmentMapper;

    /** 文件存储抽象，本期为 OSS 实现。 */
    private final FileStorageService fileStorageService;

    /** 业务时钟，写入上传时间用，禁止使用系统默认时区。 */
    private final Clock clock;

    /** 附件事件写入器，删除已关联附件时留痕；未关联附件不写时间线。 */
    private final TicketAttachEventWriter ticketAttachEventWriter;

    /**
     * 上传附件：校验白名单与大小后写入 OSS，再落元数据。
     *
     * @param file    上传文件
     * @param bizType 业务类型，缺省 TICKET
     * @return 附件信息
     * @throws BusinessException 空文件 / 类型不支持 / 文件名非法抛 400；超过大小上限抛 413；
     *                           存储不可用抛 503；写库失败抛 500
     */
    @Override
    public AttachmentVO upload(MultipartFile file, String bizType) {
        FileBizType type = FileBizType.fromOrDefault(bizType);
        Long uploaderId = UserContext.getUserId();
        String fileName = normalizeFileName(file);
        String extension = extensionOf(fileName);
        validate(file, type, extension);

        String mimeType = normalizeMimeType(file.getContentType());
        byte[] content = readBytes(file);
        StoredFile stored = fileStorageService.upload(type.getObjectDir(), fileName, mimeType, content);

        TicketAttachment attachment = new TicketAttachment();
        attachment.setUploaderId(uploaderId);
        attachment.setFileName(fileName);
        attachment.setFileUrl(stored.url());
        attachment.setFileSize((long) content.length);
        attachment.setMimeType(mimeType);
        attachment.setCreatedAt(LocalDateTime.now(clock));

        try {
            attachmentMapper.insert(attachment);
        } catch (RuntimeException e) {
            log.error("附件元数据写入失败，开始补偿删除 OSS 对象, objectKey={}, uploaderId={}",
                    stored.objectKey(), uploaderId, e);
            compensateDelete(stored);
            throw new BusinessException(500, "附件保存失败，请稍后重试");
        }

        log.info("上传附件成功, attachmentId={}, bizType={}, fileName={}, fileSize={}, uploaderId={}",
                attachment.getId(), type, fileName, attachment.getFileSize(), uploaderId);
        return AttachmentVO.builder()
                .id(attachment.getId())
                .fileName(attachment.getFileName())
                .fileUrl(attachment.getFileUrl())
                .fileSize(attachment.getFileSize())
                .mimeType(attachment.getMimeType())
                .createdAt(attachment.getCreatedAt())
                .build();
    }

    /**
     * 删除附件：校验权限后先删对象再删记录。
     *
     * @param id 附件 ID
     * @throws BusinessException 不存在或越权抛 404；存储不可用抛 503
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TicketAttachment attachment = attachmentMapper.selectById(id);
        if (attachment == null) {
            log.warn("删除附件被拒绝：附件不存在, attachmentId={}, operatorId={}", id, UserContext.getUserId());
            throw new BusinessException(404, "附件不存在");
        }

        LoginUser operator = UserContext.getRequired();
        boolean owner = Objects.equals(attachment.getUploaderId(), operator.id());
        boolean admin = Role.ADMIN.name().equals(operator.role());
        if (!owner && !admin) {
            // 越权与不存在统一 404，避免调用方枚举附件 ID
            log.warn("删除附件被拒绝：非上传人且非管理员, attachmentId={}, uploaderId={}, operatorId={}, role={}",
                    id, attachment.getUploaderId(), operator.id(), operator.role());
            throw new BusinessException(404, "附件不存在");
        }

        // 只有已关联工单的附件才进时间线；未关联附件的上传/删除都不该污染工单履历
        if (attachment.getTicketId() != null) {
            ticketAttachEventWriter.writeDelete(attachment, operator.id(),
                    LocalDateTime.now(clock).withNano(0));
        }

        fileStorageService.deleteByUrl(attachment.getFileUrl());
        attachmentMapper.deleteById(id);
        log.info("删除附件成功, attachmentId={}, ticketId={}, uploaderId={}, operatorId={}",
                id, attachment.getTicketId(), attachment.getUploaderId(), operator.id());
    }

    /**
     * 校验文件非空、扩展名白名单与大小上限。
     *
     * @param file      上传文件
     * @param type      业务类型
     * @param extension 小写扩展名，可为 null
     * @throws BusinessException 空文件 / 类型不支持抛 400；超过大小上限抛 413
     */
    private void validate(MultipartFile file, FileBizType type, String extension) {
        if (file == null || file.isEmpty()) {
            log.warn("上传附件被拒绝：文件为空, operatorId={}", UserContext.getUserId());
            throw new BusinessException(400, "文件不能为空");
        }
        if (!type.supports(extension)) {
            log.warn("上传附件被拒绝：文件类型不支持, fileName={}, bizType={}, operatorId={}",
                    file.getOriginalFilename(), type, UserContext.getUserId());
            throw new BusinessException(400, "不支持的文件类型");
        }
        if (file.getSize() > type.getMaxSizeBytes()) {
            log.warn("上传附件被拒绝：文件超过上限, fileName={}, size={}, max={}, bizType={}, operatorId={}",
                    file.getOriginalFilename(), file.getSize(), type.getMaxSizeBytes(), type, UserContext.getUserId());
            throw new BusinessException(413, "文件大小超过限制，单个文件不能超过 " + type.getMaxSizeMb() + "MB");
        }
    }

    /**
     * 读取文件字节，IO 失败转 400。
     *
     * @param file 上传文件
     * @return 文件字节
     * @throws BusinessException 读取失败时抛出 400
     */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            log.warn("上传附件被拒绝：文件读取失败, fileName={}, operatorId={}",
                    file.getOriginalFilename(), UserContext.getUserId(), e);
            throw new BusinessException(400, "文件读取失败，请重新上传");
        }
    }

    /**
     * 补偿删除已上传的 OSS 对象。
     *
     * <p>
     * 补偿本身失败只记 error，不覆盖原始的「写库失败」异常，避免调用方看到错误的原因。
     * </p>
     *
     * @param stored 已上传的对象
     */
    private void compensateDelete(StoredFile stored) {
        try {
            fileStorageService.deleteByUrl(stored.url());
        } catch (RuntimeException e) {
            log.error("补偿删除 OSS 对象失败，需人工清理, objectKey={}", stored.objectKey(), e);
        }
    }

    /**
     * 规整原始文件名：去掉客户端可能带上的路径，限制长度。
     *
     * @param file 上传文件，可为 null
     * @return 可展示的文件名，未提供时为 {@code file}
     */
    private String normalizeFileName(MultipartFile file) {
        String raw = file == null ? null : file.getOriginalFilename();
        if (raw == null || raw.isBlank()) {
            return DEFAULT_FILE_NAME;
        }
        String trimmed = raw.trim();
        int separator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name = separator >= 0 ? trimmed.substring(separator + 1) : trimmed;
        if (name.isBlank()) {
            return DEFAULT_FILE_NAME;
        }
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    /**
     * 提取小写扩展名。
     *
     * @param fileName 已规整的文件名
     * @return 小写扩展名，不含点；没有扩展名时为 null
     */
    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 规整 MIME 类型，空值兜底并截断到字段长度。
     *
     * @param raw 客户端上报的 MIME，可为 null
     * @return 可落库的 MIME 类型
     */
    private String normalizeMimeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CONTENT_TYPE;
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_MIME_TYPE_LENGTH
                ? trimmed.substring(0, MAX_MIME_TYPE_LENGTH)
                : trimmed;
    }
}
