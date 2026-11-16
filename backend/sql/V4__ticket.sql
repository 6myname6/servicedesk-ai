-- =============================================================
-- V4 工单域：ticket
-- 依据：docs/ER图.md 4.5 / 5 索引设计
-- 说明：外键一律为逻辑外键，不建物理 FK；
--       uk_ticket_no 是工单号唯一性的最终兜底
-- =============================================================

CREATE TABLE IF NOT EXISTS `ticket` (
    `id`                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `ticket_no`              VARCHAR(32)  NOT NULL                COMMENT '工单号，唯一，TKyyyyMMdd000001',
    `title`                  VARCHAR(128) NOT NULL                COMMENT '标题',
    `description`            TEXT         NOT NULL                COMMENT '问题描述',
    `category`               VARCHAR(16)  NULL     DEFAULT NULL   COMMENT '故障分类：HARDWARE/SOFTWARE/CONSUMABLE/INSTALL/COMPLAINT/OTHER',
    `urgency`                VARCHAR(8)   NULL     DEFAULT NULL   COMMENT '紧急度：P0/P1/P2/P3',
    `status`                 VARCHAR(24)  NOT NULL DEFAULT 'NEW'  COMMENT '状态：9 状态',
    `customer_id`            BIGINT       NOT NULL                COMMENT '客户，逻辑外键 customer.id',
    `device_id`              BIGINT       NULL     DEFAULT NULL   COMMENT '设备，逻辑外键 device.id',
    `reporter_id`            BIGINT       NOT NULL                COMMENT '报修人，逻辑外键 sys_user.id',
    `assignee_id`            BIGINT       NULL     DEFAULT NULL   COMMENT '当前处理工程师',
    `team_id`                BIGINT       NULL     DEFAULT NULL   COMMENT '归属团队',
    `sla_policy_id`          BIGINT       NULL     DEFAULT NULL   COMMENT '适用 SLA 策略',
    `response_deadline`      DATETIME     NULL     DEFAULT NULL   COMMENT '响应截止',
    `resolve_deadline`       DATETIME     NULL     DEFAULT NULL   COMMENT '解决截止',
    `first_response_at`      DATETIME     NULL     DEFAULT NULL   COMMENT '首次响应时间',
    `resolved_at`            DATETIME     NULL     DEFAULT NULL   COMMENT '解决时间',
    `closed_at`              DATETIME     NULL     DEFAULT NULL   COMMENT '关闭时间',
    `paused_at`              DATETIME     NULL     DEFAULT NULL   COMMENT '本次暂停开始时间',
    `paused_total_minutes`   INT          NOT NULL DEFAULT 0      COMMENT '累计暂停分钟',
    `response_escalated_at`  DATETIME     NULL     DEFAULT NULL   COMMENT '响应超时升级时间（幂等）',
    `resolve_escalated_at`   DATETIME     NULL     DEFAULT NULL   COMMENT '解决超时升级时间（幂等）',
    `ai_confidence`          DECIMAL(4,3) NULL     DEFAULT NULL   COMMENT 'AI 分类置信度',
    `ai_raw`                 JSON         NULL                    COMMENT 'AI 原始输出',
    `ai_corrected`           TINYINT      NOT NULL DEFAULT 0      COMMENT '人工是否修正过 AI 结果',
    `version`                INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
    `created_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_assignee_status` (`assignee_id`, `status`),
    KEY `idx_reporter_created` (`reporter_id`, `created_at`),
    KEY `idx_team_status` (`team_id`, `status`),
    KEY `idx_sla_response` (`response_deadline`, `first_response_at`),
    KEY `idx_sla_resolve` (`resolve_deadline`, `resolved_at`),
    KEY `idx_customer` (`customer_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单';
