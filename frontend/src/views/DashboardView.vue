<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import PrototypeBadge from '@/components/PrototypeBadge.vue'
import SlaRemaining from '@/components/SlaRemaining.vue'
import StatusTag from '@/components/StatusTag.vue'
import TrendChart from '@/components/TrendChart.vue'
import UrgencyTag from '@/components/UrgencyTag.vue'
import { demoDashboard } from '@/mock/prototype'
import { useAuthStore } from '@/stores/auth'
import { STATUS_LABELS } from '@/utils/dict'

const router = useRouter()
const auth = useAuthStore()

/** 工作台原型数据，接口就绪后改为按角色调用统计接口。 */
const data = demoDashboard()

/** 状态分布堆叠条的颜色，与 StatusTag 的语义保持一致。 */
const STATUS_COLORS = {
  NEW: '#d97706',
  ASSIGNED: '#1d4ed8',
  ACCEPTED: '#2563eb',
  IN_PROGRESS: '#f59e0b',
  WAITING_CUSTOMER: '#7c3aed',
  RESOLVED: '#059669',
  REOPENED: '#dc2626'
}

/** 当前角色对应的数据范围说明。 */
const scopeText = computed(() => {
  const teamName = auth.user?.teamName || '我的团队'
  const map = {
    ADMIN: '全公司数据范围',
    SUPERVISOR: `${teamName}数据范围`,
    ENGINEER: '我的工单与团队待办',
    REPORTER: '我的报修记录'
  }
  return map[auth.role] || '我的数据范围'
})

/** 按当前时间给问候语。 */
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 12) {
    return '上午好'
  }
  if (hour < 18) {
    return '下午好'
  }
  return '晚上好'
})

/** 顶栏日期，格式 yyyy-MM-dd。 */
const todayText = computed(() => {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
})

/** 状态分布总数，用于算堆叠条占比。 */
const distributionTotal = computed(() =>
  data.statusDistribution.reduce((sum, item) => sum + item.count, 0)
)

/**
 * 计算某个状态在堆叠条中的宽度百分比。
 *
 * @param {number} count 该状态工单数
 * @returns {string} 百分比字符串
 */
function distributionWidth(count) {
  if (distributionTotal.value === 0) {
    return '0%'
  }
  return `${((count / distributionTotal.value) * 100).toFixed(2)}%`
}

/**
 * 打开工单详情。
 *
 * @param {number} id 工单 ID
 */
function openTicket(id) {
  router.push(`/tickets/${id}`)
}
</script>

<template>
  <div class="dashboard">
    <section class="hello">
      <div class="hello__text">
        <h1>{{ greeting }}，{{ auth.user?.displayName || '同事' }}</h1>
        <p>
          {{ scopeText }} · 今天是 {{ todayText }}
          <span class="hello__dot">·</span>
          有 <strong>{{ data.kpis[1].value }}</strong> 张工单需要关注
        </p>
      </div>
      <PrototypeBadge />
    </section>

    <!-- 顶部 KPI -->
    <div class="kpi-grid">
      <article v-for="kpi in data.kpis" :key="kpi.key" class="kpi" :class="`kpi--${kpi.tone}`">
        <span class="kpi__icon">
          <el-icon><component :is="kpi.icon" /></el-icon>
        </span>
        <div class="kpi__body">
          <span class="kpi__label">{{ kpi.label }}</span>
          <strong class="kpi__value">
            {{ kpi.value }}<em>{{ kpi.unit }}</em>
          </strong>
          <span class="kpi__delta">{{ kpi.delta }}</span>
        </div>
      </article>
    </div>

    <!-- 中部：SLA 告警 + 我的待办 -->
    <div class="mid-grid">
      <section class="sd-card panel">
        <header class="sd-card__header">
          <div class="panel__title">
            <h2 class="sd-card__title">SLA 告警</h2>
            <span class="panel__count">{{ data.slaAlerts.length }} 张</span>
          </div>
          <span class="sd-card__tip">按剩余时间升序，含已超时</span>
        </header>

        <ul class="alert-list">
          <li
            v-for="alert in data.slaAlerts"
            :key="alert.id"
            class="alert"
            :class="{ 'alert--breached': alert.remainingMinutes < 0 }"
            @click="openTicket(alert.id)"
          >
            <span class="alert__urgency">
              <UrgencyTag :urgency="alert.urgency" />
            </span>
            <span class="alert__main">
              <span class="alert__title">
                {{ alert.title }}
              </span>
              <span class="alert__meta">
                <span class="mono">{{ alert.ticketNo }}</span>
                <span class="alert__sep">/</span>
                <StatusTag :status="alert.status" />
                <span class="alert__sep">/</span>
                {{ alert.assignee }}
              </span>
            </span>
            <span class="alert__sla">
              <SlaRemaining
                :minutes="alert.remainingMinutes"
                :breached="alert.remainingMinutes < 0"
                :status="alert.status"
              />
              <em class="alert__deadline">{{ alert.remainingMinutes < 0 ? '已超时' : '剩余' }}</em>
            </span>
          </li>
        </ul>

        <p v-if="data.slaAlerts.length === 0" class="panel__empty">当前没有 SLA 告警</p>
      </section>

      <section class="sd-card panel">
        <header class="sd-card__header">
          <div class="panel__title">
            <h2 class="sd-card__title">我的待办</h2>
            <span class="panel__count">{{ data.todos.length }} 项</span>
          </div>
          <router-link to="/tickets" class="panel__more">查看全部</router-link>
        </header>

        <ul v-if="data.todos.length" class="todo-list">
          <li v-for="todo in data.todos" :key="todo.id" class="todo" @click="openTicket(todo.id)">
            <span class="todo__check"><el-icon><Right /></el-icon></span>
            <span class="todo__main">
              <span class="todo__title">{{ todo.title }}</span>
              <span class="todo__meta">
                <UrgencyTag :urgency="todo.urgency" />
                <span class="mono">{{ todo.ticketNo }}</span>
              </span>
              <span class="todo__note">{{ todo.meta }}</span>
            </span>
          </li>
        </ul>

        <div v-else class="panel__empty panel__empty--tall">
          <el-icon><Coffee /></el-icon>
          <p>今天没有待处理的工单</p>
        </div>
      </section>
    </div>

    <!-- 底部：趋势 + 状态分布 -->
    <div class="bottom-grid">
      <section class="sd-card panel">
        <header class="sd-card__header">
          <h2 class="sd-card__title">近 7 天工单趋势</h2>
          <span class="sd-card__tip">单位：单</span>
        </header>
        <TrendChart :data="data.trend" :height="230" />
      </section>

      <section class="sd-card panel">
        <header class="sd-card__header">
          <h2 class="sd-card__title">工单状态分布</h2>
          <span class="sd-card__tip">共 {{ distributionTotal }} 张</span>
        </header>

        <div class="stack">
          <span
            v-for="item in data.statusDistribution"
            :key="item.status"
            class="stack__bar"
            :style="{ width: distributionWidth(item.count), background: STATUS_COLORS[item.status] }"
            :title="`${STATUS_LABELS[item.status]} ${item.count} 张`"
          ></span>
        </div>

        <ul class="legend">
          <li v-for="item in data.statusDistribution" :key="item.status" class="legend__item">
            <i class="legend__dot" :style="{ background: STATUS_COLORS[item.status] }"></i>
            <span class="legend__label">{{ STATUS_LABELS[item.status] }}</span>
            <strong class="legend__value">{{ item.count }}</strong>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 问候 */
.hello {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.hello__text h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.4px;
}

.hello__text p {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.hello__text strong {
  color: var(--sd-red);
  font-weight: 600;
}

.hello__dot {
  margin: 0 4px;
  color: #c4cddb;
}

/* KPI */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.kpi {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 18px 20px;
  border-radius: var(--sd-radius);
  background: var(--sd-card);
  box-shadow: var(--sd-shadow);
  border: 1px solid transparent;
}

.kpi__icon {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  background: var(--sd-primary-soft);
  color: var(--sd-primary);
}

.kpi__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.kpi__label {
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.kpi__value {
  margin-top: 2px;
  font-size: 26px;
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
  margin-top: 2px;
  font-size: 11px;
  color: var(--sd-text-muted);
}

.kpi--danger .kpi__icon {
  background: var(--sd-red-soft);
  color: var(--sd-red);
}

.kpi--danger .kpi__value {
  color: var(--sd-red);
}

.kpi--success .kpi__icon {
  background: var(--sd-green-soft);
  color: var(--sd-green);
}

.kpi--purple .kpi__icon {
  background: var(--sd-purple-soft);
  color: var(--sd-purple);
}

/* 面板 */
.panel__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.panel__count {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: #f2f5fa;
  color: var(--sd-text-secondary);
  font-size: 11px;
}

.panel__more {
  font-size: 12px;
}

.panel__empty {
  margin: 0;
  padding: 28px 0;
  text-align: center;
  color: var(--sd-text-muted);
  font-size: 12px;
}

.panel__empty--tall {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 48px 0;
  font-size: 22px;
}

.panel__empty--tall p {
  margin: 0;
  font-size: 12px;
}

/* SLA 告警列表 */
.alert-list,
.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.mid-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.bottom-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.alert {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 10px;
  border-radius: 9px;
  border-bottom: 1px solid #f2f5fa;
  cursor: pointer;
  transition: background 0.16s ease;
}

.alert:last-child {
  border-bottom: none;
}

.alert:hover {
  background: #f7f9ff;
}

.alert--breached {
  background: #fffafa;
}

.alert__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.alert__title {
  font-size: 13px;
  color: var(--sd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alert__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--sd-text-muted);
}

.alert__sep {
  color: #d3dbe8;
}

.alert__sla {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
  flex-shrink: 0;
}

.alert__deadline {
  font-style: normal;
  font-size: 10px;
  color: var(--sd-text-muted);
}

/* 我的待办 */
.todo {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 10px;
  border-radius: 9px;
  border-bottom: 1px solid #f2f5fa;
  cursor: pointer;
  transition: background 0.16s ease;
}

.todo:last-child {
  border-bottom: none;
}

.todo:hover {
  background: #f7f9ff;
}

.todo__check {
  width: 18px;
  height: 18px;
  margin-top: 2px;
  border-radius: 50%;
  border: 1px solid #d7dfec;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #b7c2d4;
  font-size: 10px;
  flex-shrink: 0;
}

.todo__main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.todo__title {
  font-size: 13px;
  color: var(--sd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--sd-text-muted);
}

.todo__note {
  font-size: 11px;
  color: var(--sd-text-secondary);
}

/* 状态分布 */
.stack {
  display: flex;
  height: 12px;
  border-radius: 999px;
  overflow: hidden;
  background: #f2f5fa;
  margin: 6px 0 16px;
}

.stack__bar {
  height: 100%;
  min-width: 2px;
  transition: width 0.3s ease;
}

.legend {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 18px;
}

.legend__item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.legend__dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  flex-shrink: 0;
}

.legend__label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.legend__value {
  color: var(--sd-text);
  font-weight: 600;
}

@media (max-width: 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mid-grid,
  .bottom-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
