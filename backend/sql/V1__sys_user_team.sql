-- =============================================================
-- V1 账号域：sys_user、team
-- 依据：docs/ER图.md 4.1 / 4.2 / 5 索引设计
-- 说明：外键一律为逻辑外键，不建物理 FK（见 ER图.md 第 1 节）
-- 顺序：team 先建，sys_user.team_id 逻辑引用 team.id；
--       team.supervisor_id 逻辑引用 sys_user.id，二者互为逻辑引用
-- =============================================================

CREATE TABLE IF NOT EXISTS `team` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`          VARCHAR(64)  NOT NULL                COMMENT '团队名，唯一',
    `supervisor_id` BIGINT       NULL     DEFAULT NULL   COMMENT '主管用户 ID，逻辑外键 sys_user.id',
    `description`   VARCHAR(255) NULL     DEFAULT NULL   COMMENT '描述',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_supervisor` (`supervisor_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '团队';

CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(32)  NOT NULL                COMMENT '登录名，唯一',
    `password_hash` VARCHAR(100) NOT NULL                COMMENT '密码哈希，BCrypt',
    `display_name`  VARCHAR(32)  NOT NULL                COMMENT '显示名',
    `role`          VARCHAR(16)  NOT NULL                COMMENT '角色：REPORTER/ENGINEER/SUPERVISOR/ADMIN',
    `team_id`       BIGINT       NULL     DEFAULT NULL   COMMENT '所属团队，逻辑外键 team.id',
    `phone`         VARCHAR(20)  NULL     DEFAULT NULL   COMMENT '手机号',
    `email`         VARCHAR(64)  NULL     DEFAULT NULL   COMMENT '邮箱',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1 启用 / 0 停用',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_team_role` (`team_id`, `role`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户账号';
