package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * 文件业务类型（2 种）：决定对象键目录、扩展名白名单与单文件大小上限。
 *
 * <p>
 * 属于枚举字典层。取值必须与接口文档 4.1 的 {@code bizType} 参数、开发文档 F-05 / K-02
 * 的验收标准保持一致：工单附件 10MB，知识库文档 20MB。
 * </p>
 *
 * <p>
 * 关键约束：只按扩展名做白名单校验（开发文档口径），不信任客户端上报的 MIME；
 * 扩展名统一小写比较，{@code jpg} 与 {@code JPG} 等价。
 * </p>
 */
public enum FileBizType {

    /** 工单附件：图片 / PDF / Word / Markdown，单文件 ≤ 10MB。 */
    TICKET("ticket", Set.of("jpg", "jpeg", "png", "pdf", "docx", "md"), 10L * 1024 * 1024),

    /** 知识库文档：PDF / Word / Markdown，单文件 ≤ 20MB。 */
    KB("kb", Set.of("pdf", "docx", "md"), 20L * 1024 * 1024);

    /** 1MB 的字节数，用于计算展示用的上限。 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 对象键目录，小写，参与 {@code {bizType}/{yyyyMM}/{uuid}.{ext}} 拼装。 */
    private final String objectDir;

    /** 允许的扩展名白名单，均为小写且不含点。 */
    private final Set<String> allowedExtensions;

    /** 单文件大小上限（字节）。 */
    private final long maxSizeBytes;

    /**
     * 构造业务类型。
     *
     * @param objectDir         对象键目录
     * @param allowedExtensions 扩展名白名单
     * @param maxSizeBytes      单文件上限（字节）
     */
    FileBizType(String objectDir, Set<String> allowedExtensions, long maxSizeBytes) {
        this.objectDir = objectDir;
        this.allowedExtensions = allowedExtensions;
        this.maxSizeBytes = maxSizeBytes;
    }

    /**
     * 把请求参数解析为枚举，缺省为工单附件。
     *
     * @param raw 请求参数，可为 null 或空白
     * @return 解析后的类型；null / 空白返回 {@link #TICKET}
     * @throws BusinessException 取值不在字典内时抛出 400
     */
    public static FileBizType fromOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return TICKET;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (FileBizType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new BusinessException(400, "不支持的业务类型: " + raw);
    }

    /**
     * 判断扩展名是否在白名单内。
     *
     * @param extension 小写扩展名，不含点，可为 null
     * @return true 表示允许上传
     */
    public boolean supports(String extension) {
        return extension != null && allowedExtensions.contains(extension);
    }

    /**
     * 对象键目录。
     *
     * @return 小写目录名，例如 {@code ticket}
     */
    public String getObjectDir() {
        return objectDir;
    }

    /**
     * 单文件大小上限（字节）。
     *
     * @return 上限字节数
     */
    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    /**
     * 单文件大小上限（MB），用于拼装提示语。
     *
     * @return 上限 MB 数
     */
    public long getMaxSizeMb() {
        return maxSizeBytes / BYTES_PER_MB;
    }
}
