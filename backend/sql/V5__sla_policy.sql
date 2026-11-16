-- =============================================================
-- V5 SLA 域：sla_policy
-- 依据：docs/ER图.md 4.8 / 5 索引设计（uk_priority）
-- 说明：建单（T-01）按 ticket.urgency 匹配本表 priority，
--      取 response_minutes / resolve_minutes 计算 ticket 的两个 deadline；
--      优先级唯一由 uk_priority 兜底，不建物理 FK。
--      默认 4 条策略（产品文档 7.3）不在此文件插入，见种子数据缺口。
-- =============================================================

CREATE TABLE IF NOT EXISTS `sla_policy` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`             VARCHAR(64) NOT NULL                COMMENT '策略名，如 P1 标准',
    `priority`         VARCHAR(8)  NOT NULL                COMMENT '适用紧急度：P0/P1/P2/P3，唯一',
    `response_minutes` INT         NOT NULL                COMMENT '响应时限（分钟），必须为正数',
    `resolve_minutes`  INT         NOT NULL                COMMENT '解决时限（分钟），必须为正数',
    `enabled`          TINYINT     NOT NULL DEFAULT 1      COMMENT '是否启用：1 启用 / 0 停用，停用后新单不使用',
    `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_priority` (`priority`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = 'SLA 策略';
