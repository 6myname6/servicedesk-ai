/**
 * 前端原型示例数据。
 *
 * <p>仅服务于尚未提供后端接口的页面（P-02 工作台、P-04 工单详情、P-06/P-07 知识库、
 * P-08 报表、C-01 Agent 抽屉），让整体页面原型可以独立浏览与演示。
 * 后端接口就绪后删除本文件，页面改为调用 {@code src/api/} 下的真实接口。</p>
 *
 * <p>约定：时间字段统一按后端序列化格式 {@code yyyy-MM-ddTHH:mm:ss} 生成，
 * 且以「当前时间 + 偏移分钟」构造，保证 SLA 倒计时、时间线在新的一天打开也合理。</p>
 */

/**
 * 补零。
 *
 * @param {number} value 数字
 * @returns {string} 两位字符串
 */
function pad(value) {
  return String(value).padStart(2, '0')
}

/**
 * 把 Date 序列化为后端同款时间字符串（本地时区、无时区后缀）。
 *
 * @param {Date} date 时间
 * @returns {string} yyyy-MM-ddTHH:mm:ss
 */
function serialize(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * 相对当前时间偏移若干分钟。
 *
 * @param {number} minutes 偏移分钟，负数为过去
 * @returns {string} 后端格式时间字符串
 */
function offsetTime(minutes) {
  const date = new Date()
  date.setMinutes(date.getMinutes() + minutes)
  return serialize(date)
}

/**
 * 生成近 N 天的「MM-DD」标签。
 *
 * @param {number} count 天数
 * @returns {string[]} 由早到晚的日期标签
 */
function recentDayLabels(count) {
  const labels = []
  for (let i = count - 1; i >= 0; i -= 1) {
    const date = new Date()
    date.setDate(date.getDate() - i)
    labels.push(`${pad(date.getMonth() + 1)}-${pad(date.getDate())}`)
  }
  return labels
}

/**
 * 工作台原型数据。
 *
 * @returns {{kpis: object[], slaAlerts: object[], todos: object[], trend: object[], statusDistribution: object[]}}
 *          工作台各区块数据
 */
export function demoDashboard() {
  return {
    kpis: [
      {
        key: 'pending',
        label: '待处理工单',
        value: 18,
        unit: '单',
        delta: '今日新增 3 单',
        tone: 'primary',
        icon: 'Tickets'
      },
      {
        key: 'dueSoon',
        label: '即将超时 / 已超时',
        value: 4,
        unit: '单',
        delta: '其中 2 单已超时',
        tone: 'danger',
        icon: 'AlarmClock'
      },
      {
        key: 'resolved',
        label: '今日解决',
        value: 12,
        unit: '单',
        delta: '较昨日 +2',
        tone: 'success',
        icon: 'CircleCheck'
      },
      {
        key: 'sla',
        label: 'SLA 达成率',
        value: 96.4,
        unit: '%',
        delta: '本月 182 / 189',
        tone: 'purple',
        icon: 'DataLine'
      }
    ],
    slaAlerts: [
      {
        id: 1042,
        ticketNo: 'TK20261121000042',
        title: '产线扫码枪无法连接 MES，影响装配工位报工',
        urgency: 'P1',
        status: 'IN_PROGRESS',
        assignee: '陈立',
        remainingMinutes: 68,
        deadline: offsetTime(68)
      },
      {
        id: 1039,
        ticketNo: 'TK20261121000039',
        title: '财务共享中心打印机频繁卡纸',
        urgency: 'P2',
        status: 'IN_PROGRESS',
        assignee: '王敏',
        remainingMinutes: 26,
        deadline: offsetTime(26)
      },
      {
        id: 1035,
        ticketNo: 'TK20261121000035',
        title: '仓库 PDA 无法登录 WMS 系统',
        urgency: 'P1',
        status: 'ACCEPTED',
        assignee: '刘海',
        remainingMinutes: 12,
        deadline: offsetTime(12)
      },
      {
        id: 1031,
        ticketNo: 'TK20261121000031',
        title: '会议室投屏器无信号输出',
        urgency: 'P2',
        status: 'NEW',
        assignee: '待派单',
        remainingMinutes: -18,
        deadline: offsetTime(-18)
      },
      {
        id: 1027,
        ticketNo: 'TK20261120000027',
        title: 'ERP 客户端打开采购单时报错 500',
        urgency: 'P1',
        status: 'IN_PROGRESS',
        assignee: '陈立',
        remainingMinutes: -46,
        deadline: offsetTime(-46)
      }
    ],
    todos: [
      {
        id: 1042,
        ticketNo: 'TK20261121000042',
        title: '产线扫码枪无法连接 MES',
        urgency: 'P1',
        meta: '客户等待现场处理 · 陈立'
      },
      {
        id: 1046,
        ticketNo: 'TK20261121000046',
        title: '新员工笔记本装机与域账号开通',
        urgency: 'P3',
        meta: '计划今日 16:00 上门'
      },
      {
        id: 1044,
        ticketNo: 'TK20261121000044',
        title: '考勤机数据同步延迟 2 小时',
        urgency: 'P2',
        meta: '需确认网络策略变更窗口'
      },
      {
        id: 1030,
        ticketNo: 'TK20261120000030',
        title: '客服耳机通话杂音',
        urgency: 'P3',
        meta: '备件已到货，等待客户确认时间'
      }
    ],
    trend: recentDayLabels(7).map((label, index) => {
      const created = [14, 18, 11, 22, 16, 9, 13][index]
      const resolved = [11, 15, 13, 19, 17, 10, 12][index]
      return { label, created, resolved }
    }),
    statusDistribution: [
      { status: 'NEW', count: 5 },
      { status: 'ASSIGNED', count: 4 },
      { status: 'ACCEPTED', count: 3 },
      { status: 'IN_PROGRESS', count: 9 },
      { status: 'RESOLVED', count: 7 },
      { status: 'CLOSED', count: 6 },
      { status: 'REJECTED', count: 2 },
      { status: 'CANCELLED', count: 1 }
    ]
  }
}

/**
 * 工单详情原型数据。
 *
 * @param {string|number} id 路由里的工单 ID，仅用于拼工单号
 * @returns {object} 工单详情，字段对齐接口文档 GET /tickets/{id} 的目标结构
 */
export function demoTicketDetail(id) {
  const numericId = Number(id) || 1042
  // 工单号规则：TK + yyyyMMdd + 6 位序号，与接口文档示例一致
  const ticketNo = `TK20261121${String(numericId).padStart(6, '0')}`

  return {
    id: numericId,
    ticketNo,
    title: '产线扫码枪无法连接 MES，影响装配工位报工',
    description:
      '2 号装配工位的 Zebra DS2208 扫码枪昨天下午开始无法把条码回传 MES，换了一根数据线后仍然只能扫码不报工。' +
      '目前工位改用人工记录，每小时约影响 60 件报工，请尽快上门排查。',
    category: 'HARDWARE',
    urgency: 'P1',
    status: 'IN_PROGRESS',
    source: 'WEB',
    createdAt: offsetTime(-95),
    customer: {
      id: 7,
      name: '宁波华翔精密制造有限公司',
      level: 'KEY',
      contact: '张工',
      phone: '138****6621'
    },
    device: {
      id: 21,
      name: 'Zebra DS2208 扫码枪',
      model: 'DS2208-SR',
      sn: 'SN-2024-008731',
      warrantyStatus: 'EXPIRED',
      purchasedAt: '2024-03-18'
    },
    assignee: { id: 4, name: '陈立', teamName: '华东一线支持组' },
    reporter: { id: 12, name: '张工' },
    sla: {
      responseMet: true,
      respondedAt: offsetTime(-72),
      responseDeadline: offsetTime(-65),
      responseRemaining: null,
      resolveDeadline: offsetTime(68),
      resolveRemaining: 68,
      resolveTotalMinutes: 240
    },
    attachments: [
      { id: 1, name: '扫码枪指示灯.jpg', size: 428_000 },
      { id: 2, name: 'MES 报错截图.png', size: 1_180_000 }
    ],
    timeline: [
      {
        id: 1,
        kind: 'event',
        title: '工单创建',
        operator: '张工',
        roleLabel: '报修人',
        at: offsetTime(-95),
        content: '通过服务台提交报修，标题与描述来自建单表单。'
      },
      {
        id: 2,
        kind: 'event',
        title: 'AI 分类定级',
        operator: 'AI 坐席',
        roleLabel: '系统',
        at: offsetTime(-94),
        content: '分类：硬件故障；紧急度：P1；摘要：扫码枪与 MES 通讯中断；置信度 0.92。'
      },
      {
        id: 3,
        kind: 'event',
        title: '派单给陈立',
        operator: '李静',
        roleLabel: '主管',
        at: offsetTime(-88),
        content: '指派团队：华东一线支持组；指派工程师：陈立。'
      },
      {
        id: 4,
        kind: 'event',
        title: '陈立接单',
        operator: '陈立',
        roleLabel: '工程师',
        at: offsetTime(-72),
        content: '首次响应耗时 23 分钟，满足 P1 响应 SLA（30 分钟）。'
      },
      {
        id: 5,
        kind: 'comment',
        title: '现场排查',
        operator: '陈立',
        roleLabel: '工程师',
        at: offsetTime(-40),
        content:
          '已到现场。扫码枪指示灯常亮但无数据回传，设备管理器中 USB 转串口驱动显示黄色感叹号。' +
          '已重装驱动并更换数据线，正在复测。',
        internal: false
      },
      {
        id: 6,
        kind: 'comment',
        title: '内部备注',
        operator: '陈立',
        roleLabel: '工程师',
        at: offsetTime(-18),
        content:
          '客户现场有备用 DS2208 两台。若驱动重装无效直接替换，避免停线超过 2 小时触发 P1 解决超时。',
        internal: true
      }
    ],
    aiSuggestions: [
      {
        id: 1,
        title: 'DS2208 驱动安装与 USB 转串口排查',
        snippet:
          '设备管理器出现黄色感叹号时，先卸载旧驱动并重启，再安装 Zebra CDC 驱动 3.4.0；确认 USB 端口供电正常。',
        source: '知识库 / 扫码设备 / DS2208 故障排查',
        similarity: 0.94,
        adopted: false
      },
      {
        id: 2,
        title: 'MES 条码回传失败的常见原因',
        snippet:
          '依次检查扫码枪键盘口模式、MES 采集服务状态、工位终端网络策略；三者任一异常都会出现「能扫不能报工」。',
        source: '知识库 / MES / 条码采集',
        similarity: 0.89,
        adopted: false
      },
      {
        id: 3,
        title: '扫码枪替换作业指导书',
        snippet: '备用设备替换前先导出配置条码，替换后需重新扫描工位绑定条码，避免上报到错误工位。',
        source: '知识库 / 设备运维 / 备件替换',
        similarity: 0.81,
        adopted: false
      },
      {
        id: 4,
        title: 'P1 工单停线时长与升级规则',
        snippet: 'P1 工单解决时限 4 小时，剩余 60 分钟时系统自动升级并通知团队主管。',
        source: '知识库 / SLA / 升级规则',
        similarity: 0.72,
        adopted: false
      },
      {
        id: 5,
        title: '现场服务安全与静电防护',
        snippet: '进入产线需佩戴防静电手环，插拔 USB 设备前先断开终端电源，避免主板静电击穿。',
        source: '知识库 / 现场规范 / 安全作业',
        similarity: 0.66,
        adopted: false
      }
    ],
    rating: null
  }
}

/**
 * 知识库文章列表原型数据。
 *
 * @returns {object[]} 文章列表，含正文，供编辑抽屉直接回填
 */
export function demoKbArticles() {
  return [
    {
      id: 1,
      title: 'Zebra DS2208 扫码枪驱动安装与故障排查',
      category: 'HARDWARE',
      status: 'READY',
      chunks: 18,
      author: '李静',
      updatedAt: offsetTime(-26 * 60),
      content:
        '# DS2208 扫码枪故障排查\n\n' +
        '## 一、现象\n\n- 能扫码但不回传数据\n- 设备管理器 USB 转串口出现黄色感叹号\n\n' +
        '## 二、处理步骤\n\n1. 卸载旧版驱动并重启终端\n2. 安装 Zebra CDC 驱动 3.4.0\n' +
        '3. 重新扫描工位绑定条码\n4. 在 MES 采集服务中确认设备上线\n\n' +
        '> 注意：插拔设备前先断开终端电源，佩戴防静电手环。'
    },
    {
      id: 2,
      title: 'MES 报工终端离线处理手册',
      category: 'SOFTWARE',
      status: 'READY',
      chunks: 12,
      author: '陈立',
      updatedAt: offsetTime(-3 * 24 * 60),
      content:
        '# MES 报工终端离线处理\n\n1. 确认终端网络指示灯\n2. 执行 `ping mes-gateway` 检查网络\n' +
        '3. 重启采集服务 `MESAgent`\n4. 仍离线则切换备用终端并登记设备故障\n'
    },
    {
      id: 3,
      title: '打印机耗材更换与回收规范',
      category: 'CONSUMABLE',
      status: 'INDEXING',
      chunks: 6,
      author: '王敏',
      updatedAt: offsetTime(-55),
      content: '# 耗材更换规范\n\n- 更换前登记旧耗材序列号\n- 废粉盒、硒鼓按危废回收\n- 更换后打印测试页并拍照留档\n'
    },
    {
      id: 4,
      title: '新设备安装调试验收清单',
      category: 'INSTALL',
      status: 'PENDING',
      chunks: 0,
      author: '刘海',
      updatedAt: offsetTime(-20),
      content: '# 安装调试验收清单\n\n- 设备序列号与档案一致\n- 网络与权限开通\n- 客户签字确认\n'
    },
    {
      id: 5,
      title: '客户投诉升级响应话术',
      category: 'COMPLAINT',
      status: 'FAILED',
      chunks: 0,
      author: '李静',
      updatedAt: offsetTime(-2 * 24 * 60),
      failReason: 'PDF 解析失败：文件已加密，未提取到文本内容',
      content: '# 投诉升级话术\n\n（原文件为加密 PDF，需重新上传可复制的文档）\n'
    },
    {
      id: 6,
      title: 'SLA 策略与升级规则说明',
      category: 'OTHER',
      status: 'READY',
      chunks: 9,
      author: '管理员',
      updatedAt: offsetTime(-6 * 24 * 60),
      content:
        '# SLA 策略\n\n| 紧急度 | 响应 | 解决 |\n| --- | --- | --- |\n| P0 | 15 分钟 | 2 小时 |\n' +
        '| P1 | 30 分钟 | 4 小时 |\n| P2 | 2 小时 | 8 小时 |\n| P3 | 8 小时 | 24 小时 |\n'
    }
  ]
}

/**
 * 运营报表原型数据。
 *
 * @param {'today'|'7d'|'30d'|'custom'} range 时间范围
 * @returns {object} 报表 KPI、趋势、分类分布、工程师负载与故障 TopN
 */
export function demoReport(range = '7d') {
  const preset = {
    today: {
      kpis: [
        { key: 'sla', label: 'SLA 达成率', value: '100', unit: '%', delta: '12 / 12' },
        { key: 'response', label: '平均响应', value: '16', unit: '分钟', delta: '优于目标 14 分钟' },
        { key: 'resolve', label: '平均解决', value: '2.4', unit: '小时', delta: '优于目标 1.6 小时' },
        { key: 'total', label: '工单总量', value: '13', unit: '单', delta: '较昨日 +4' }
      ],
      trend: ['08:00', '10:00', '12:00', '14:00', '16:00', '18:00', '20:00', '22:00'].map(
        (label, index) => ({
          label,
          created: [1, 3, 2, 4, 1, 2, 0, 0][index],
          resolved: [0, 2, 1, 3, 2, 2, 1, 1][index]
        })
      )
    },
    '7d': {
      kpis: [
        { key: 'sla', label: 'SLA 达成率', value: '96.4', unit: '%', delta: '环比 +1.8%' },
        { key: 'response', label: '平均响应', value: '21', unit: '分钟', delta: '目标 30 分钟' },
        { key: 'resolve', label: '平均解决', value: '3.6', unit: '小时', delta: '目标 6 小时' },
        { key: 'total', label: '工单总量', value: '189', unit: '单', delta: '环比 +12.5%' }
      ],
      trend: recentDayLabels(7).map((label, index) => ({
        label,
        created: [18, 26, 21, 32, 27, 24, 19][index],
        resolved: [16, 23, 25, 29, 30, 22, 21][index]
      }))
    },
    '30d': {
      kpis: [
        { key: 'sla', label: 'SLA 达成率', value: '94.1', unit: '%', delta: '环比 -0.6%' },
        { key: 'response', label: '平均响应', value: '24', unit: '分钟', delta: '目标 30 分钟' },
        { key: 'resolve', label: '平均解决', value: '4.2', unit: '小时', delta: '目标 6 小时' },
        { key: 'total', label: '工单总量', value: '726', unit: '单', delta: '环比 +8.2%' }
      ],
      trend: ['11-01', '11-04', '11-07', '11-10', '11-13', '11-16', '11-19', '11-21'].map(
        (label, index) => ({
          label,
          created: [68, 74, 61, 82, 70, 76, 65, 71][index],
          resolved: [62, 71, 66, 78, 73, 72, 70, 68][index]
        })
      )
    }
  }

  const data = preset[range] || preset['7d']

  return {
    ...data,
    categories: [
      { label: '硬件故障', value: 62 },
      { label: '软件故障', value: 41 },
      { label: '耗材更换', value: 28 },
      { label: '安装调试', value: 19 },
      { label: '投诉建议', value: 12 },
      { label: '其他', value: 9 }
    ],
    engineers: [
      { name: '陈立', team: '华东一线支持组', total: 32, avgHours: '3.1', slaRate: '98.2' },
      { name: '王敏', team: '华东一线支持组', total: 28, avgHours: '3.8', slaRate: '96.4' },
      { name: '刘海', team: '华南现场服务组', total: 24, avgHours: '4.2', slaRate: '95.1' },
      { name: '赵磊', team: '华北现场服务组', total: 19, avgHours: '4.6', slaRate: '93.7' }
    ],
    topFaults: [
      { model: 'Zebra DS2208 扫码枪', count: 14, customers: 5, trend: '+3' },
      { model: 'HP LaserJet M404dn', count: 11, customers: 7, trend: '+1' },
      { model: 'Lenovo ThinkPad T14', count: 9, customers: 6, trend: '0' },
      { model: 'Honeywell 1450g 扫码枪', count: 7, customers: 3, trend: '+2' },
      { model: 'Dell U2723QE 显示器', count: 5, customers: 4, trend: '-1' }
    ]
  }
}

/**
 * Agent 抽屉的初始会话。
 *
 * @returns {{messages: object[], steps: object[]}} 初始消息与本次 run 的工具步骤
 */
export function demoAgentSeed() {
  return {
    messages: [
      {
        id: 1,
        role: 'assistant',
        at: offsetTime(-2),
        content:
          '你好，我是服务台 AI 坐席。可以帮你查询工单进度、创建工单或检索知识库；涉及写操作我会先向你确认。'
      }
    ],
    steps: []
  }
}

/**
 * 按用户输入生成 Agent 的模拟回复。
 *
 * <p>原型只覆盖三条典型链路：查工单、建工单（需确认）、知识检索。</p>
 *
 * @param {string} text 用户输入
 * @returns {{reply: object, steps: object[], confirm: object|null}} 回复、工具步骤与待确认写操作
 */
export function demoAgentReply(text) {
  const keyword = String(text || '')

  if (keyword.includes('建单') || keyword.includes('创建') || keyword.includes('报修')) {
    return {
      reply: {
        role: 'assistant',
        content: '已整理出建单信息。create_ticket 属于写操作，请确认后我再执行。'
      },
      steps: [
        { name: 'search_customer', label: '匹配客户：宁波华翔精密制造有限公司', cost: '0.6s', status: 'success' },
        { name: 'create_ticket', label: '等待用户确认（写操作）', cost: '—', status: 'waiting' }
      ],
      confirm: {
        tool: 'create_ticket',
        target: '宁波华翔精密制造有限公司 / 2 号装配工位',
        params: [
          { key: 'title', value: '产线扫码枪无法连接 MES' },
          { key: 'category', value: 'HARDWARE' },
          { key: 'urgency', value: 'P1' }
        ]
      }
    }
  }

  if (keyword.includes('知识') || keyword.includes('怎么') || keyword.includes('如何')) {
    return {
      reply: {
        role: 'assistant',
        content:
          '知识库命中 2 条相关片段：\n' +
          '1.《DS2208 驱动安装与 USB 转串口排查》第 3 节，相似度 0.94\n' +
          '2.《MES 条码回传失败的常见原因》，相似度 0.89\n' +
          '引用来源：知识库 / 扫码设备 / DS2208 故障排查。'
      },
      steps: [
        { name: 'search_kb', label: '向量检索 Top5：命中 2 条', cost: '1.2s', status: 'success' }
      ],
      confirm: null
    }
  }

  return {
    reply: {
      role: 'assistant',
      content:
        '已查询工单 TK20261121000042：当前状态「处理中」，工程师陈立已到现场，' +
        '解决 SLA 剩余 68 分钟。最近一条处理记录：已重装 USB 转串口驱动，正在复测。'
    },
    steps: [
      { name: 'get_ticket', label: '查询 TK20261121000042', cost: '0.4s', status: 'success' }
    ],
    confirm: null
  }
}
