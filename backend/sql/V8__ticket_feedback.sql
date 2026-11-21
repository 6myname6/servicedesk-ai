-- =============================================================
-- V8 工单域：ticket_feedback
-- 依据：docs/ER图.md 4.14 / 5 索引设计（uk_ticket）
-- 说明：一单一评，ticket_id 唯一索引是并发下的最终兜底；
--       ticket_id 为逻辑外键，不建物理 FK。
-- =============================================================

CREATE TABLE IF NOT EXISTS `ticket_feedback` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `ticket_id`  BIGINT       NOT NULL                COMMENT '工单，逻辑外键 ticket.id，唯一',
    `score`      TINYINT      NOT NULL                COMMENT '评分，1-5',
    `comment`    VARCHAR(500) NULL     DEFAULT NULL   COMMENT '评价内容',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket` (`ticket_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单回访评价';
