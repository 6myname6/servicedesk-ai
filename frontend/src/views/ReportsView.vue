<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

import PrototypeBadge from '@/components/PrototypeBadge.vue'
import TrendChart from '@/components/TrendChart.vue'
import { demoReport } from '@/mock/prototype'

/** 时间范围选项。 */
const RANGE_OPTIONS = [
  { value: 'today', label: '今日' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: 'custom', label: '自定义' }
]

/** 示例问题，点击即发起问数。 */
const EXAMPLE_QUESTIONS = [
  '近 7 天各分类工单量是多少？',
  'SLA 达成率最低的团队是哪个？',
  '故障最多的设备型号 Top5'
]

/**
 * NL2SQL 的模拟结果集。
 *
 * <p>原型只按问题关键词命中三套固定结果，真实实现由后端 AI-04 生成并校验 SQL。</p>
 */
const NL_RESULTS = {
  categories: {
    sql:
      'SELECT category, COUNT(*) AS total\n' +
      'FROM ticket\n' +
      "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)\n" +
      'GROUP BY category\n' +
      'ORDER BY total DESC;',
    columns: [
      { key: 'category', label: '故障分类' },
      { key: 'total', label: '工单量' }
    ],
    rows: [
      { category: '硬件故障', total: 62 },
      { category: '软件故障', total: 41 },
      { category: '耗材更换', total: 28 },
      { category: '安装调试', total: 19 },
      { category: '投诉建议', total: 12 },
      { category: '其他', total: 9 }
    ],
    summary: '近 7 天共 171 张工单，硬件故障占 36.3%，是当前最主要的故障类型。'
  },
  teams: {
    sql:
      'SELECT t.name AS team_name,\n' +
      '       ROUND(SUM(s.met) / COUNT(*) * 100, 1) AS sla_rate\n' +
      'FROM ticket tkt\n' +
      'JOIN team t ON t.id = tkt.team_id\n' +
      'JOIN sla_result s ON s.ticket_id = tkt.id\n' +
      'GROUP BY t.name\n' +
      'ORDER BY sla_rate ASC;',
    columns: [
      { key: 'team', label: '团队' },
      { key: 'slaRate', label: 'SLA 达成率' },
      { key: 'total', label: '工单量' }
    ],
    rows: [
      { team: '华北现场服务组', slaRate: '92.1%', total: 38 },
      { team: '华南现场服务组', slaRate: '94.8%', total: 46 },
      { team: '华东一线支持组', slaRate: '97.2%', total: 68 }
    ],
    summary: '华北现场服务组 SLA 达成率最低（92.1%），且工单量不低，建议核查该组的响应排班。'
  },
  devices: {
    sql:
      'SELECT d.model, COUNT(*) AS fault_count,\n' +
      '       COUNT(DISTINCT tkt.customer_id) AS customer_count\n' +
      'FROM ticket tkt\n' +
      'JOIN device d ON d.id = tkt.device_id\n' +
      "WHERE tkt.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)\n" +
      'GROUP BY d.model\n' +
      'ORDER BY fault_count DESC\n' +
      'LIMIT 5;',
    columns: [
      { key: 'model', label: '设备型号' },
      { key: 'faultCount', label: '故障次数' },
      { key: 'customerCount', label: '涉及客户' }
    ],
    rows: [
      { model: 'Zebra DS2208 扫码枪', faultCount: 14, customerCount: 5 },
      { model: 'HP LaserJet M404dn', faultCount: 11, customerCount: 7 },
      { model: 'Lenovo ThinkPad T14', faultCount: 9, customerCount: 6 },
      { model: 'Honeywell 1450g 扫码枪', faultCount: 7, customerCount: 3 },
      { model: 'Dell U2723QE 显示器', faultCount: 5, customerCount: 4 }
    ],
    summary: '近 30 天 Zebra DS2208 故障 14 次、涉及 5 家客户，建议评估该批次设备的驱动兼容性。'
  }
}

/** 当前时间范围。 */
const range = ref('7d')

/** 自定义时间范围（仅 range === 'custom' 时使用）。 */
const customRange = ref([])

/** NL2SQL 输入。 */
const question = ref('')

/** NL2SQL 生成中。 */
const nlLoading = ref(false)

/** NL2SQL 生成结果。 */
const nlResult = ref(null)

/** 报表数据；自定义范围在原型阶段回落到近 7 天。 */
const data = computed(() => demoReport(range.value === 'custom' ? '7d' : range.value))

/** 分类分布中的最大值，用于算条形宽度。 */
const categoryMax = computed(() =>
  Math.max(...data.value.categories.map((item) => item.value), 1)
)

/** 工程师负载中的最大值，用于算进度条宽度。 */
const engineerMax = computed(() =>
  Math.max(...data.value.engineers.map((item) => item.total), 1)
)

/**
 * 选择时间范围。
 *
 * @param {string} value 范围编码
 */
function selectRange(value) {
  range.value = value
  nlResult.value = null
}

/**
 * 计算分类条的宽度百分比。
 *
 * @param {number} value 工单量
 * @returns {string} 百分比字符串
 */
function categoryWidth(value) {
  return `${((value / categoryMax.value) * 100).toFixed(1)}%`
}

/**
 * 计算工程师负载的宽度百分比。
 *
 * @param {number} total 工单量
 * @returns {string} 百分比字符串
 */
function engineerWidth(total) {
  return `${((total / engineerMax.value) * 100).toFixed(1)}%`
}

/**
 * 按问题关键词挑一套模拟结果。
 *
 * @param {string} text 问题文本
 * @returns {object} NL2SQL 模拟结果
 */
function pickResult(text) {
  if (text.includes('团队')) {
    return NL_RESULTS.teams
  }
  if (text.includes('设备') || text.includes('型号')) {
    return NL_RESULTS.devices
  }
  return NL_RESULTS.categories
}

/**
 * 发起智能问数。
 *
 * @param {string} [text] 指定问题，来自示例 chips；为空时取输入框
 */
async function ask(text) {
  const query = (text ?? question.value).trim()
  if (!query) {
    ElMessage.warning('请输入想查询的问题')
    return
  }
  if (nlLoading.value) {
    return
  }

  question.value = query
  nlResult.value = null
  nlLoading.value = true
  try {
    await new Promise((resolve) => window.setTimeout(resolve, 900))
    nlResult.value = pickResult(query)
  } finally {
    nlLoading.value = false
  }
}
</script>

<template>
  <div class="reports">
    <div class="reports__head">
      <div>
        <h1 class="reports__title">
          运营报表
          <PrototypeBadge />
        </h1>
        <p class="reports__desc">SLA 达成、工单量、故障分布与工程师负载，数据范围按当前角色裁剪。</p>
      </div>

      <div class="reports__range">
        <div class="range">
          <button
            v-for="item in RANGE_OPTIONS"
            :key="item.value"
            type="button"
            class="range__item"
            :class="{ 'is-active': range === item.value }"
            @click="selectRange(item.value)"
          >
            {{ item.label }}
          </button>
        </div>
        <el-date-picker
          v-if="range === 'custom'"
          v-model="customRange"
          type="daterange"
          size="small"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          class="reports__picker"
        />
      </div>
    </div>

    <!-- KPI -->
    <div class="kpi-grid">
      <article v-for="kpi in data.kpis" :key="kpi.key" class="kpi">
        <span class="kpi__label">{{ kpi.label }}</span>
        <strong class="kpi__value">
          {{ kpi.value }}<em>{{ kpi.unit }}</em>
        </strong>
        <span class="kpi__delta">{{ kpi.delta }}</span>
      </article>
    </div>

    <div class="grid-2">
      <section class="sd-card">
        <header class="sd-card__header">
          <h2 class="sd-card__title">工单量趋势</h2>
          <span class="sd-card__tip">单位：单</span>
        </header>
        <TrendChart :data="data.trend" :height="248" />
      </section>

      <section class="sd-card">
        <header class="sd-card__header">
          <h2 class="sd-card__title">故障分类分布</h2>
          <span class="sd-card__tip">按工单量</span>
        </header>
        <ul class="cat-list">
          <li v-for="item in data.categories" :key="item.label" class="cat">
            <span class="cat__label">{{ item.label }}</span>
            <span class="cat__track">
              <i class="cat__bar" :style="{ width: categoryWidth(item.value) }"></i>
            </span>
            <span class="cat__value mono">{{ item.value }}</span>
          </li>
        </ul>
      </section>
    </div>

    <div class="grid-2">
      <section class="sd-card">
        <header class="sd-card__header">
          <h2 class="sd-card__title">工程师负载</h2>
          <span class="sd-card__tip">按处理量排序</span>
        </header>

        <el-table :data="data.engineers" style="width: 100%">
          <el-table-column label="工程师" width="130">
            <template #default="{ row }">
              <div class="eng">
                <span class="eng__avatar">{{ row.name.slice(0, 1) }}</span>
                <span class="eng__name">{{ row.name }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="团队" min-width="140">
            <template #default="{ row }">
              <span class="text-secondary">{{ row.team }}</span>
            </template>
          </el-table-column>

          <el-table-column label="处理量" width="150">
            <template #default="{ row }">
              <div class="load">
                <span class="load__track">
                  <i class="load__bar" :style="{ width: engineerWidth(row.total) }"></i>
                </span>
                <span class="mono">{{ row.total }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="平均解决" width="96">
            <template #default="{ row }">
              <span class="mono">{{ row.avgHours }} h</span>
            </template>
          </el-table-column>

          <el-table-column label="SLA" width="84" align="right">
            <template #default="{ row }">
              <span class="rate" :class="{ 'rate--warn': Number(row.slaRate) < 95 }">
                {{ row.slaRate }}%
              </span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="sd-card">
        <header class="sd-card__header">
          <h2 class="sd-card__title">故障设备 Top5</h2>
          <span class="sd-card__tip">近 30 天</span>
        </header>

        <ol class="faults">
          <li v-for="(item, index) in data.topFaults" :key="item.model" class="fault">
            <span class="fault__rank" :class="{ 'fault__rank--top': index < 3 }">{{ index + 1 }}</span>
            <span class="fault__main">
              <span class="fault__model">{{ item.model }}</span>
              <span class="fault__meta">{{ item.count }} 次 · {{ item.customers }} 家客户</span>
            </span>
            <span
              class="fault__trend"
              :class="{ 'fault__trend--up': item.trend.startsWith('+'), 'fault__trend--down': item.trend.startsWith('-') }"
            >
              {{ item.trend }}
            </span>
          </li>
        </ol>
      </section>
    </div>

    <!-- NL2SQL -->
    <section class="sd-card nlq">
      <header class="sd-card__header">
        <h2 class="sd-card__title">
          智能问数
          <span class="nlq__badge">AI-04 · NL2SQL</span>
        </h2>
        <span class="sd-card__tip">只生成 SELECT，结果超过 200 行会截断</span>
      </header>

      <div class="nlq__examples">
        <button
          v-for="example in EXAMPLE_QUESTIONS"
          :key="example"
          type="button"
          class="nlq__chip"
          @click="ask(example)"
        >
          {{ example }}
        </button>
      </div>

      <div class="nlq__input">
        <el-input
          v-model="question"
          placeholder="用一句话描述你想看的数据，例如：近 30 天各团队的 SLA 达成率"
          clearable
          @keyup.enter="ask()"
        >
          <template #prefix>
            <el-icon><MagicStick /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" :loading="nlLoading" @click="ask()">
          {{ nlLoading ? '生成中…' : '生成查询' }}
        </el-button>
      </div>

      <div v-if="nlResult" class="nlq__result">
        <div class="nlq__block">
          <span class="nlq__block-title">生成的 SQL</span>
          <pre class="nlq__sql mono">{{ nlResult.sql }}</pre>
        </div>

        <div class="nlq__block">
          <span class="nlq__block-title">查询结果</span>
          <el-table :data="nlResult.rows" size="small" style="width: 100%">
            <el-table-column
              v-for="column in nlResult.columns"
              :key="column.key"
              :prop="column.key"
              :label="column.label"
              min-width="120"
            />
          </el-table>
        </div>

        <p class="nlq__summary">
          <el-icon><Opportunity /></el-icon>
          {{ nlResult.summary }}
        </p>
      </div>

      <p v-else-if="!nlLoading" class="nlq__empty">
        输入问题或点击示例后生成，原型阶段返回固定演示结果。
      </p>
    </section>
  </div>
</template>

<style scoped>
.reports {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.reports__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.reports__title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.reports__desc {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.reports__range {
  display: flex;
  align-items: center;
  gap: 10px;
}

.range {
  display: inline-flex;
  padding: 3px;
  border-radius: 9px;
  background: #eef2f8;
}

.range__item {
  padding: 5px 14px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--sd-text-secondary);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.range__item.is-active {
  background: #fff;
  color: var(--sd-primary);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.08);
}

.reports__picker {
  width: 220px;
}

/* KPI */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.kpi {
  display: flex;
  flex-direction: column;
  padding: 18px 20px;
  border-radius: var(--sd-radius);
  background: var(--sd-card);
  box-shadow: var(--sd-shadow);
}

.kpi__label {
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.kpi__value {
  margin-top: 4px;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.kpi__value em {
  margin-left: 3px;
  font-style: normal;
  font-size: 12px;
  font-weight: 400;
  color: var(--sd-text-muted);
}

.kpi__delta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--sd-text-muted);
}

/* 两列 */
.grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

/* 分类条 */
.cat-list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.cat {
  display: grid;
  grid-template-columns: 66px minmax(0, 1fr) 40px;
  align-items: center;
  gap: 10px;
}

.cat__label {
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.cat__track {
  height: 8px;
  border-radius: 999px;
  background: #f2f5fa;
  overflow: hidden;
}

.cat__bar {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #6d95ff, #2f6bff);
}

.cat__value {
  font-size: 12px;
  color: var(--sd-text);
  text-align: right;
}

/* 工程师 */
.eng {
  display: flex;
  align-items: center;
  gap: 8px;
}

.eng__avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #eaf0ff;
  color: var(--sd-primary);
  font-size: 11px;
  font-weight: 600;
}

.eng__name {
  font-size: 13px;
}

.load {
  display: flex;
  align-items: center;
  gap: 8px;
}

.load__track {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: #f2f5fa;
  overflow: hidden;
}

.load__bar {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #14b8a6;
}

.rate {
  font-size: 12px;
  font-weight: 600;
  color: var(--sd-green);
}

.rate--warn {
  color: var(--sd-orange);
}

/* 故障 Top5 */
.faults {
  list-style: none;
  margin: 2px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.fault {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px dashed #f0f3f8;
}

.fault:last-child {
  border-bottom: none;
}

.fault__rank {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #f2f5fa;
  color: var(--sd-text-muted);
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.fault__rank--top {
  background: var(--sd-orange-soft);
  color: #b45309;
}

.fault__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.fault__model {
  font-size: 12px;
  color: var(--sd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fault__meta {
  font-size: 11px;
  color: var(--sd-text-muted);
}

.fault__trend {
  font-size: 11px;
  color: var(--sd-text-muted);
}

.fault__trend--up {
  color: var(--sd-red);
}

.fault__trend--down {
  color: var(--sd-green);
}

/* NL2SQL */
.nlq__badge {
  margin-left: 8px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--sd-purple-soft);
  color: var(--sd-purple);
  font-size: 10px;
  font-weight: 500;
  vertical-align: middle;
}

.nlq__examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.nlq__chip {
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--sd-line);
  background: #f9fbfe;
  color: var(--sd-text-secondary);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease;
}

.nlq__chip:hover {
  border-color: var(--sd-primary);
  color: var(--sd-primary);
}

.nlq__input {
  display: flex;
  gap: 10px;
}

.nlq__result {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed var(--sd-line);
}

.nlq__block-title {
  display: block;
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--sd-text-muted);
}

.nlq__sql {
  margin: 0;
  padding: 12px 14px;
  border-radius: 9px;
  background: #0e2233;
  color: #c9e4ff;
  font-size: 11px;
  line-height: 1.8;
  overflow-x: auto;
}

.nlq__summary {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--sd-green-soft);
  color: #06614a;
  font-size: 12px;
  line-height: 1.7;
}

.nlq__empty {
  margin: 14px 0 0;
  padding: 18px;
  border-radius: 9px;
  background: #f9fbfe;
  border: 1px dashed var(--sd-line);
  text-align: center;
  color: var(--sd-text-muted);
  font-size: 12px;
}

@media (max-width: 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
