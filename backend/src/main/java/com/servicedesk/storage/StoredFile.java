package com.servicedesk.storage;

/**
 * 一次上传的结果。
 *
 * <p>
 * 属于存储层的值对象。{@code objectKey} 用于排查与删除，{@code url} 落库到
 * {@code ticket_attachment.file_url} 供前端直接访问。
 * </p>
 *
 * @param objectKey 对象键，形如 {@code ticket/202610/{uuid}.jpg}
 * @param url       公网访问地址
 */
public record StoredFile(String objectKey, String url) {
}
