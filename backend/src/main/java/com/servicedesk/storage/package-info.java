/**
 * 文件存储抽象与阿里云 OSS 实现。
 *
 * <p>
 * 业务层只依赖 {@code FileStorageService}，对象键规则与公网 URL 由实现负责；
 * 换 MinIO / S3 时新增实现即可，接口与表结构不变。
 * </p>
 */
package com.servicedesk.storage;
