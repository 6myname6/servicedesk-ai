-- =============================================================
-- V3 设备域：device
-- 依据：docs/ER图.md 4.4 / 5 索引设计
-- 说明：外键为逻辑外键，不建物理 FK；customer 需先建（V2）
-- =============================================================

CREATE TABLE IF NOT EXISTS `device` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `customer_id`   BIGINT       NOT NULL                COMMENT '所属客户，逻辑外键 customer.id',
    `model`         VARCHAR(64)  NULL     DEFAULT NULL   COMMENT '型号',
    `serial_no`     VARCHAR(64)  NOT NULL                COMMENT '序列号，唯一',
    `purchase_date` DATE         NULL     DEFAULT NULL   COMMENT '购买日期',
    `warranty_end`  DATE         NULL     DEFAULT NULL   COMMENT '保修到期日',
    `location`      VARCHAR(255) NULL     DEFAULT NULL   COMMENT '安装位置',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/SCRAPPED',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_serial_no` (`serial_no`),
    KEY `idx_customer` (`customer_id`),
    KEY `idx_warranty` (`warranty_end`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '设备档案';
