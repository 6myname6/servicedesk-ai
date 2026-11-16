-- =============================================================
-- V6 附件域：ticket_attachment
-- 依据：docs/ER图.md 4.7 / 5 索引设计（idx_ticket）
-- 说明：ticket_id 为逻辑外键，不建物理 FK；
--       上传时尚未建单则为 NULL，建单接口回填（接口文档 2.2 imageUrls）；
--       文件本体存 OSS，本表只存元数据与公网 URL。
-- =============================================================

CREATE TABLE IF NOT EXISTS `ticket_attachment` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `ticket_id`   BIGINT       NULL     DEFAULT NULL   COMMENT '工单，逻辑外键 ticket.id；未关联时为 NULL',
    `uploader_id` BIGINT       NOT NULL                COMMENT '上传人，逻辑外键 sys_user.id',
    `file_name`   VARCHAR(255) NOT NULL                COMMENT '原始文件名',
    `file_url`    VARCHAR(512) NOT NULL                COMMENT '访问 URL（OSS 公网地址）',
    `file_size`   BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    `mime_type`   VARCHAR(64)  NULL     DEFAULT NULL   COMMENT 'MIME 类型',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`),
    KEY `idx_ticket` (`ticket_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单附件';
