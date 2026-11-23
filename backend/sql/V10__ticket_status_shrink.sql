-- =============================================================
-- V10 工单状态机收缩（RC-001）
-- 依据：docs/需求变化.md RC-001、开发文档 7.13、ER 图第 6 节
-- 说明：删除 WAITING_CUSTOMER / REOPENED 两个状态，存量行统一改到 IN_PROGRESS；
--       paused_at / paused_total_minutes 保留列不删，归零后代码不再写入；
--       ticket_event 是追加审计，历史行不改写：旧事件类型保留原文，
--       新代码只会写入 12.1 字典内的类型，时间线按原值展示。
-- =============================================================

-- 1) 旧状态归入处理中：两种状态都表示工单仍在工程师手里
UPDATE `ticket`
SET `status` = 'IN_PROGRESS'
WHERE `status` IN ('WAITING_CUSTOMER', 'REOPENED');

-- 2) 暂停机制整体移除：死列归零，读接口不再依赖它们
UPDATE `ticket`
SET `paused_at` = NULL,
    `paused_total_minutes` = 0
WHERE `paused_at` IS NOT NULL
   OR `paused_total_minutes` <> 0;

-- 3) 校验：不再有旧状态与暂停残留
SELECT 'legacy_status' AS check_name, COUNT(*) AS row_count
FROM `ticket`
WHERE `status` IN ('WAITING_CUSTOMER', 'REOPENED')
UNION ALL
SELECT 'paused_leftover', COUNT(*)
FROM `ticket`
WHERE `paused_at` IS NOT NULL
   OR `paused_total_minutes` <> 0;
