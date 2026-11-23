-- =============================================================
-- V9 账号域：sys_user.customer_id
-- 依据：docs/ER图.md 4.1（所属客户）、2.x 关系（客户 1:N 用户）
-- 说明：可空，逻辑外键 customer.id，不建物理 FK；
--       与 team_id 互斥：账号要么属于内部团队，要么是客户联系人，二者只能有一个；
--       互斥由业务层校验（400），不用 CHECK 约束兜底，便于后续放开；
--       账号量级百级，暂不为该列单独建索引。
-- =============================================================

ALTER TABLE `sys_user`
    ADD COLUMN `customer_id` BIGINT NULL DEFAULT NULL COMMENT '所属客户，逻辑外键 customer.id；与 team_id 互斥' AFTER `team_id`;
