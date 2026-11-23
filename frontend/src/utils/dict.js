/**
 * 枚举字典与展示文案。
 *
 * <p>取值必须与开发文档 12.1、ER 图第 6 节、后端枚举类保持一致；
 * 新增枚举时三处同步，本文件同步补文案。</p>
 */

/** 角色编码 → 中文名。 */
export const ROLE_LABELS = {
  REPORTER: '报修人',
  ENGINEER: '工程师',
  SUPERVISOR: '主管',
  ADMIN: '管理员'
}

/** 角色下拉选项。 */
export const ROLE_OPTIONS = Object.entries(ROLE_LABELS).map(([value, label]) => ({ value, label }))

/** 故障分类编码 → 中文名。 */
export const CATEGORY_LABELS = {
  HARDWARE: '硬件故障',
  SOFTWARE: '软件故障',
  CONSUMABLE: '耗材更换',
  INSTALL: '安装调试',
  COMPLAINT: '投诉建议',
  OTHER: '其他'
}

/** 故障分类下拉选项。 */
export const CATEGORY_OPTIONS = Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value, label }))

/** 工单状态编码 → 中文名（RC-001：8 个状态）。 */
export const STATUS_LABELS = {
  NEW: '待派单',
  ASSIGNED: '已派单',
  ACCEPTED: '已接单',
  IN_PROGRESS: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已完成',
  REJECTED: '不通过',
  CANCELLED: '已取消'
}

/** 工单状态下拉选项。 */
export const STATUS_OPTIONS = Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))

/** 终态集合：列表 SLA 列显示终态文案而不是倒计时。 */
export const TERMINAL_STATUSES = ['CLOSED', 'REJECTED', 'CANCELLED']

/** 时间线事件类型 → 中文名，与开发文档 12.1 的事件字典一致。 */
export const EVENT_LABELS = {
  CREATE: '建单',
  UPDATE: '编辑工单',
  ASSIGN: '派单',
  ACCEPT: '接单',
  START: '开始处理',
  COMMENT: '处理记录',
  RESOLVE: '解决',
  CLOSE: '验收通过',
  REJECT: '验收不通过',
  CANCEL: '取消工单',
  FEEDBACK: '评价',
  ESCALATE: '超时升级',
  AI_SUGGEST: 'AI 建议',
  ATTACH: '附件'
}

/** 紧急度编码 → 中文名。 */
export const URGENCY_LABELS = {
  P0: 'P0',
  P1: 'P1',
  P2: 'P2',
  P3: 'P3'
}

/** 紧急度下拉选项。 */
export const URGENCY_OPTIONS = Object.keys(URGENCY_LABELS).map((value) => ({ value, label: value }))

/** 紧急度说明，用于建单页按钮旁的提示。 */
export const URGENCY_HINTS = {
  P0: '停机、安全、多人受影响；响应 15 分钟 / 解决 2 小时',
  P1: '主要功能不可用；响应 30 分钟 / 解决 4 小时',
  P2: '一般故障；响应 2 小时 / 解决 8 小时',
  P3: '咨询、优化建议；响应 8 小时 / 解决 24 小时'
}

/**
 * SLA 预览配置，取自开发文档 7.3 的默认策略。
 *
 * <p>建单页只做展示预估，真正的 deadline 由后端按 sla_policy 表计算并随详情返回。</p>
 */
export const SLA_POLICY_PREVIEW = {
  P0: { name: 'P0 紧急', responseMinutes: 15, resolveMinutes: 120 },
  P1: { name: 'P1 标准', responseMinutes: 30, resolveMinutes: 240 },
  P2: { name: 'P2 一般', responseMinutes: 120, resolveMinutes: 480 },
  P3: { name: 'P3 低优', responseMinutes: 480, resolveMinutes: 1440 }
}

/** 客户等级编码 → 中文名。 */
export const LEVEL_LABELS = {
  KEY: '重点客户',
  NORMAL: '普通客户'
}

/** 客户等级下拉选项。 */
export const LEVEL_OPTIONS = Object.entries(LEVEL_LABELS).map(([value, label]) => ({ value, label }))

/** 设备状态编码 → 中文名。 */
export const DEVICE_STATUS_LABELS = {
  ACTIVE: '在用',
  SCRAPPED: '已报废'
}

/** 设备状态下拉选项。 */
export const DEVICE_STATUS_OPTIONS = Object.entries(DEVICE_STATUS_LABELS).map(([value, label]) => ({
  value,
  label
}))

/** 保修状态编码 → 中文名。 */
export const WARRANTY_STATUS_LABELS = {
  IN_WARRANTY: '保修中',
  EXPIRED: '已过保',
  UNKNOWN: '未登记'
}
