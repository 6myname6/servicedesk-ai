-- =============================================================
-- V2 客户域：customer
-- 依据：docs/ER图.md 4.3 / 5 索引设计
-- 说明：外键一律为逻辑外键，不建物理 FK（见 ER图.md 第 1 节）
-- 顺序：customer 先建，后续 device / ticket 通过 customer_id 逻辑引用
-- =============================================================

CREATE TABLE IF NOT EXISTS `customer` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`       VARCHAR(64)  NOT NULL                COMMENT '客户名',
    `contact`    VARCHAR(32)  NULL     DEFAULT NULL   COMMENT '联系人',
    `phone`      VARCHAR(20)  NULL     DEFAULT NULL   COMMENT '电话',
    `address`    VARCHAR(255) NULL     DEFAULT NULL   COMMENT '地址',
    `level`      VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '等级：KEY/NORMAL',
    `remark`     VARCHAR(255) NULL     DEFAULT NULL   COMMENT '备注',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_name` (`name`),
    KEY `idx_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '客户';
