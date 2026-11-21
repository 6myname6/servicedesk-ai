-- =============================================================
-- V7 工单域：ticket_event
-- 依据：docs/ER图.md 4.6 / 5 索引设计（idx_ticket_created）
-- 说明：ticket_id 与 operator_id 均为逻辑外键，不建物理 FK；
--       时间线只追加不修改，内部备注用 metadata.internal=true 标记；
--       系统事件（超时升级、自动恢复）operator_id 为 NULL。
-- =============================================================

CREATE TABLE IF NOT EXISTS `ticket_event` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `ticket_id`   BIGINT      NOT NULL                COMMENT '工单，逻辑外键 ticket.id',
    `type`        VARCHAR(24) NOT NULL                COMMENT '事件类型：CREATE/ASSIGN/ACCEPT/START/WAIT_CUSTOMER/RESUME/COMMENT/RESOLVE/CLOSE/REOPEN/CANCEL/ESCALATE/AI_SUGGEST/ATTACH',
    `from_status` VARCHAR(24) NULL     DEFAULT NULL   COMMENT '变更前状态',
    `to_status`   VARCHAR(24) NULL     DEFAULT NULL   COMMENT '变更后状态',
    `operator_id` BIGINT      NULL     DEFAULT NULL   COMMENT '操作人，逻辑外键 sys_user.id；系统事件为 NULL',
    `content`     TEXT        NULL                    COMMENT '备注 / 处理记录 / 取消原因',
    `metadata`    JSON        NULL                    COMMENT '扩展字段：AI 建议、附件 ID、升级类型、internal 标记',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_ticket_created` (`ticket_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '工单事件（时间线）';
