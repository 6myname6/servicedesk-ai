-- =============================================================
-- 演示种子数据：4 角色账号 + 2 团队 + 30 客户 + 50 设备 + 200 工单 + 4 条默认 SLA 策略
-- 依据：产品文档.md 7.3（默认 SLA 策略）/ 11（种子数据口径）、开发排期.md 11/06 切片
--
-- 使用前提：V1~V5 已执行（表必须存在）。
-- 使用范围：**仅本地开发 / 演示库**。脚本先清空再写入，可重复执行；
--          不要对测试以外有意义数据的库执行。
-- 统一口令：Sd123456（8 位，满足接口文档 1.1.1 的 8-32 位校验；BCrypt 存储，见 sys_user.password_hash）
-- 幂等性：先 DELETE 再按固定主键 INSERT，重复执行结果一致。
-- =============================================================

SET NAMES utf8mb4;

-- ---------- 0. 清空（顺序无所谓：表之间只有逻辑外键） ----------
DELETE FROM `ticket`;
DELETE FROM `device`;
DELETE FROM `customer`;
DELETE FROM `sla_policy`;
UPDATE `team` SET `supervisor_id` = NULL;
DELETE FROM `sys_user`;
DELETE FROM `team`;

-- ---------- 1. 团队（主管在用户插入后回填，避免循环引用） ----------
INSERT INTO `team` (`id`, `name`, `supervisor_id`, `description`) VALUES
    (1, '华东售后一组', NULL, '负责九江、南昌、上饶片区'),
    (2, '华东售后二组', NULL, '负责赣州、吉安、抚州片区');

-- ---------- 2. 账号：四角色各至少一个，工程师按团队分开便于演示派单 ----------
INSERT INTO `sys_user` (`id`, `username`, `password_hash`, `display_name`, `role`, `team_id`, `phone`, `email`, `status`) VALUES
    (1, 'admin',   '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '系统管理员', 'ADMIN',      NULL, '13800000001', 'admin@servicedesk.local', 1),
    (2, 'sup1',    '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '王主管',     'SUPERVISOR', 1,    '13800000002', 'sup1@servicedesk.local', 1),
    (3, 'sup2',    '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '李主管',     'SUPERVISOR', 2,    '13800000003', 'sup2@servicedesk.local', 1),
    (4, 'eng1',    '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '张工',       'ENGINEER',   1,    '13800000004', 'eng1@servicedesk.local', 1),
    (5, 'eng2',    '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '陈工',       'ENGINEER',   1,    '13800000005', 'eng2@servicedesk.local', 1),
    (6, 'eng3',    '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '刘工',       'ENGINEER',   2,    '13800000006', 'eng3@servicedesk.local', 1),
    (7, 'rep1',    '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '赵报修',     'REPORTER',   NULL, '13800000007', 'rep1@servicedesk.local', 1),
    (8, 'rep2',    '$2a$10$lnwgBrad33vH0psAUfxGI.fnzGMgkQ62tzgcONve02QDCsldI2Rui', '孙报修',     'REPORTER',   NULL, '13800000008', 'rep2@servicedesk.local', 1);

-- 主管回填：team.supervisor_id 与 sys_user.team_id 互为逻辑引用，必须先有人再有指向
UPDATE `team` SET `supervisor_id` = 2 WHERE `id` = 1;
UPDATE `team` SET `supervisor_id` = 3 WHERE `id` = 2;

-- ---------- 3. 客户 30 家（名称由城市 + 行业组合，保证唯一） ----------
INSERT INTO `customer` (`id`, `name`, `contact`, `phone`, `address`, `level`, `remark`)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 30
)
SELECT
    n,
    CONCAT(
        ELT(1 + ((n - 1) % 11), '九江', '南昌', '赣州', '上饶', '宜春', '吉安', '抚州', '景德镇', '萍乡', '新余', '鹰潭'),
        ELT(1 + ((n - 1) % 12), '精工机械', '恒信电子', '华远实业', '德昌纺织', '鑫源食品', '泰和建材', '金鹏化工', '陶瓷集团', '安源煤机', '铜业加工', '长江物流', '南康家具'),
        '有限公司'
    ),
    ELT(1 + ((n - 1) % 10), '张伟', '李娜', '王强', '刘敏', '陈杰', '杨帆', '赵磊', '周涛', '吴静', '徐亮'),
    CONCAT('079', 1 + (n % 9), '-', LPAD((n * 137) % 10000000, 8, '0')),
    CONCAT(ELT(1 + ((n - 1) % 11), '九江市', '南昌市', '赣州市', '上饶市', '宜春市', '吉安市', '抚州市', '景德镇市', '萍乡市', '新余市', '鹰潭市'), '工业园区 ', n, ' 号'),
    IF(n % 5 = 0, 'KEY', 'NORMAL'),
    '演示种子数据'
FROM seq;

-- ---------- 4. 设备 50 台（客户 1~20 各 2 台，21~30 各 1 台） ----------
INSERT INTO `device` (`id`, `customer_id`, `model`, `serial_no`, `purchase_date`, `warranty_end`, `location`, `status`)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 50
)
SELECT
    n,
    ((n - 1) % 30) + 1,
    ELT(1 + (n % 6), 'AC-2000', 'AC-3000', 'PR-500', 'SC-100', 'MX-800', 'DV-20'),
    CONCAT('SN-2024-', LPAD(n, 4, '0')),
    DATE_SUB(CURDATE(), INTERVAL (n * 40) DAY),
    DATE_ADD(DATE_SUB(CURDATE(), INTERVAL (n * 40) DAY), INTERVAL 2 YEAR),
    CONCAT('车间 ', 1 + (n % 5), ' 区'),
    IF(n % 10 = 0, 'SCRAPPED', 'ACTIVE')
FROM seq;

-- ---------- 5. 默认 SLA 策略（产品文档 7.3：P0 15/120、P1 30/240、P2 120/480、P3 480/1440） ----------
INSERT INTO `sla_policy` (`id`, `name`, `priority`, `response_minutes`, `resolve_minutes`, `enabled`) VALUES
    (1, 'P0 紧急', 'P0', 15,  120,  1),
    (2, 'P1 标准', 'P1', 30,  240,  1),
    (3, 'P2 一般', 'P2', 120, 480,  1),
    (4, 'P3 低优', 'P3', 480, 1440, 1);

-- ---------- 6. 工单 200 张 ----------
-- 时间统一回推 72 小时起、每单间隔 7 小时，避免与当天 Redis 日序列生成的工单号撞号。
-- 状态按 n % 9 轮转，覆盖全部 9 个状态；deadline 按 SLA 策略分钟数计算。
-- 工单号序号段取全局序号 n，只保证唯一，不代表「当日第 n 单」——种子数据不占用 Redis 日序列。
INSERT INTO `ticket`
    (`id`, `ticket_no`, `title`, `description`, `category`, `urgency`, `status`,
     `customer_id`, `device_id`, `reporter_id`, `assignee_id`, `team_id`, `sla_policy_id`,
     `response_deadline`, `resolve_deadline`, `first_response_at`, `resolved_at`, `closed_at`,
     `paused_at`, `paused_total_minutes`, `version`, `created_at`, `updated_at`)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 200
),
base AS (
    SELECT
        n,
        DATE_SUB(NOW(), INTERVAL (n * 7 + 72) HOUR) AS created_at,
        ((n - 1) % 30) + 1 AS customer_id,
        IF(n % 10 < 7,
           (SELECT d.`id` FROM `device` d WHERE d.`customer_id` = ((n - 1) % 30) + 1 ORDER BY d.`id` LIMIT 1),
           NULL) AS device_id,
        IF(n % 2 = 0, 8, 7) AS reporter_id,
        ELT(1 + (n % 6), 'HARDWARE', 'SOFTWARE', 'CONSUMABLE', 'INSTALL', 'COMPLAINT', 'OTHER') AS category,
        ELT(1 + (n % 4), 'P0', 'P1', 'P2', 'P3') AS urgency,
        ELT(1 + (n % 9), 'NEW', 'ASSIGNED', 'ACCEPTED', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED', 'REOPENED', 'CANCELLED') AS status,
        ELT(1 + (n % 9), '设备无法开机', '运行异响', '系统频繁报错', '耗材更换申请', '新设备安装调试', '账单与合同咨询', '上门维修预约', '软件版本升级', '服务态度投诉') AS subject
    FROM seq
),
calc AS (
    SELECT
        b.*,
        CASE
            WHEN b.status IN ('NEW', 'REOPENED', 'CANCELLED') THEN NULL
            ELSE 4 + (b.n % 3)
        END AS assignee_id
    FROM base b
)
SELECT
    c.n,
    CONCAT('TK', DATE_FORMAT(c.created_at, '%Y%m%d'), LPAD(c.n, 6, '0')),
    CONCAT(c.subject, ' - ', c.customer_id, ' 号客户'),
    CONCAT('客户反馈：', c.subject, '。已电话确认现象，现场环境与复现步骤待工程师核实。'),
    c.category,
    c.urgency,
    c.status,
    c.customer_id,
    c.device_id,
    c.reporter_id,
    c.assignee_id,
    CASE
        WHEN c.status IN ('NEW', 'REOPENED', 'CANCELLED') THEN NULL
        WHEN 4 + (c.n % 3) = 6 THEN 2
        ELSE 1
    END,
    CASE c.urgency WHEN 'P0' THEN 1 WHEN 'P1' THEN 2 WHEN 'P2' THEN 3 ELSE 4 END,
    DATE_ADD(c.created_at, INTERVAL (CASE c.urgency WHEN 'P0' THEN 15 WHEN 'P1' THEN 30 WHEN 'P2' THEN 120 ELSE 480 END) MINUTE),
    DATE_ADD(c.created_at, INTERVAL (CASE c.urgency WHEN 'P0' THEN 120 WHEN 'P1' THEN 240 WHEN 'P2' THEN 480 ELSE 1440 END) MINUTE),
    CASE WHEN c.status IN ('ASSIGNED', 'ACCEPTED', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED')
         THEN DATE_ADD(c.created_at, INTERVAL 20 MINUTE) ELSE NULL END,
    CASE WHEN c.status IN ('RESOLVED', 'CLOSED')
         THEN DATE_ADD(c.created_at, INTERVAL 300 MINUTE) ELSE NULL END,
    CASE WHEN c.status = 'CLOSED'
         THEN DATE_ADD(c.created_at, INTERVAL 1800 MINUTE) ELSE NULL END,
    CASE WHEN c.status = 'WAITING_CUSTOMER'
         THEN DATE_ADD(c.created_at, INTERVAL 120 MINUTE) ELSE NULL END,
    CASE WHEN c.status = 'WAITING_CUSTOMER' THEN 60 ELSE 0 END,
    0,
    c.created_at,
    c.created_at
FROM calc c;

-- ---------- 7. 校验：期望 customer=30 / device=50 / ticket=200 / sys_user=8 / team=2 / sla_policy=4 ----------
SELECT 'customer' AS table_name, COUNT(*) AS row_count FROM `customer`
UNION ALL SELECT 'device', COUNT(*) FROM `device`
UNION ALL SELECT 'ticket', COUNT(*) FROM `ticket`
UNION ALL SELECT 'sys_user', COUNT(*) FROM `sys_user`
UNION ALL SELECT 'team', COUNT(*) FROM `team`
UNION ALL SELECT 'sla_policy', COUNT(*) FROM `sla_policy`;
