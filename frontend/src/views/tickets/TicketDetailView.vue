<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import PrototypeBadge from '@/components/PrototypeBadge.vue'
import StatusTag from '@/components/StatusTag.vue'
import UrgencyTag from '@/components/UrgencyTag.vue'
import { demoTicketDetail } from '@/mock/prototype'
import {
  CATEGORY_LABELS,
  LEVEL_LABELS,
  STATUS_LABELS,
  STATUS_OPTIONS,
  TERMINAL_STATUSES,
  WARRANTY_STATUS_LABELS
} from '@/utils/dict'
import { formatDateTime, formatFileSize, formatMinutes, parseDateTime } from '@/utils/format'

/**
 * 工单详情：状态操作的全部动作定义。
 *
 * <p>target 取自开发文档 7.13 的五动作状态机，原型只做本页状态与时间线的本地变更。</p>
 */
const ACTIONS = {
  dispatch: { label: '派单', target: 'ASSIGNED', type: 'primary' },
  accept: { label: '接单', target: 'ACCEPTED', type: 'primary' },
  start: { label: '开始处理', target: 'IN_PROGRESS', type: 'primary' },
  resolve: { label: '解决', target: 'RESOLVED', type: 'success' },
  close: { label: '验收通过', target: 'CLOSED', type: 'success' },
  reject: { label: '验收不通过', target: 'REJECTED', type: 'danger' },
  cancel: { label: '取消工单', target: 'CANCELLED', type: 'danger' }
}

/** 各状态可执行的动作，顺序即按钮顺序。 */
const STATUS_ACTIONS = {
  NEW: ['dispatch', 'cancel'],
  ASSIGNED: ['accept', 'cancel'],
  ACCEPTED: ['start', 'cancel'],
  IN_PROGRESS: ['resolve', 'cancel'],
  RESOLVED: ['close', 'reject'],
  // 三个终态没有出边；不通过后只能新建工单
  CLOSED: [],
  REJECTED: [],
  CANCELLED: []
}

/** 页面标题与单号来源；接口就绪后改为 GET /tickets/{id}。 */
const route = useRoute()
const router = useRouter()

/** 工单详情数据。 */
const ticket = ref(demoTicketDetail(route.params.id))

/** 原型预览状态：允许切换以查看不同状态下的操作区。 */
const previewStatus = ref(ticket.value.status)

/** 处理记录输入。 */
const draft = reactive({ content: '', internal: false })

/** AI 建议的处理结果：id → adopted / ignored。 */
const suggestionState = reactive({})

/** 是否展开全部 AI 建议。 */
const showAllSuggestions = ref(false)

/** 报修人评价。 */
const rating = reactive({ score: 5, comment: '' })

/** 当前状态可执行的动作列表。 */
const availableActions = computed(() =>
  (STATUS_ACTIONS[previewStatus.value] || []).map((key) => ({ key, ...ACTIONS[key] }))
)

/** 当前分类中文名。 */
const categoryLabel = computed(() => CATEGORY_LABELS[ticket.value.category] || ticket.value.category)

/** 客户等级中文名。 */
const levelLabel = computed(() => LEVEL_LABELS[ticket.value.customer.level] || '普通客户')

/** 设备保修状态中文名。 */
const warrantyLabel = computed(
  () => WARRANTY_STATUS_LABELS[ticket.value.device.warrantyStatus] || '未登记'
)

/** 首次响应耗时（分钟）。 */
const responseUsedMinutes = computed(() => {
  const start = parseDateTime(ticket.value.createdAt)
  const end = parseDateTime(ticket.value.sla.respondedAt)
  if (!start || !end) {
    return null
  }
  return Math.max(0, Math.round((end.getTime() - start.getTime()) / 60000))
})

/** 解决时钟已消耗比例，用于进度条。 */
const resolvePercent = computed(() => {
  const total = ticket.value.sla.resolveTotalMinutes
  const remaining = ticket.value.sla.resolveRemaining
  if (!total || remaining === null || remaining === undefined) {
    return 100
  }
  return Math.min(100, Math.max(0, ((total - remaining) / total) * 100))
})

/** 是否终态：终态不再显示解决倒计时。 */
const isTerminal = computed(() => TERMINAL_STATUSES.includes(previewStatus.value))

/** 当前展示的 AI 建议。 */
const visibleSuggestions = computed(() =>
  showAllSuggestions.value
    ? ticket.value.aiSuggestions
    : ticket.value.aiSuggestions.slice(0, 3)
)

/** 返回工单列表。 */
function backToList() {
  router.push('/tickets')
}

/**
 * 切换原型预览状态，同步工单对象上的状态字段。
 *
 * @param {string} value 目标状态
 */
function handlePreviewStatus(value) {
  ticket.value.status = value
}

/**
 * 执行状态操作：先二次确认，再本地流转并追加时间线。
 *
 * @param {{key: string, label: string, target: string}} action 动作定义
 */
async function runAction(action) {
  const fromLabel = STATUS_LABELS[previewStatus.value] || previewStatus.value
  const toLabel = STATUS_LABELS[action.target] || action.target
  const extra =
    action.key === 'dispatch'
      ? '，指派给陈立（华东一线支持组）'
      : action.key === 'reject'
        ? '，不通过后需新建工单'
        : ''
  try {
    await ElMessageBox.confirm(
      `当前状态「${fromLabel}」→「${toLabel}」${extra}。确认执行「${action.label}」吗？`,
      '状态流转确认',
      {
        confirmButtonText: '确认执行',
        cancelButtonText: '取消',
        type: action.type === 'danger' ? 'warning' : 'info'
      }
    )
  } catch {
    return
  }

  previewStatus.value = action.target
  ticket.value.status = action.target
  ticket.value.timeline.push({
    id: Date.now(),
    kind: 'event',
    title: action.label,
    operator: '当前登录人',
    roleLabel: '主管',
    at: new Date(),
    content: `状态由「${fromLabel}」变更为「${toLabel}」。`
  })
  ElMessage.success(`已${action.label}，当前状态「${toLabel}」`)
}

/**
 * 采纳或忽略一条 AI 建议。
 *
 * @param {object} suggestion 建议对象
 * @param {'adopted'|'ignored'} state 处理结果
 */
function markSuggestion(suggestion, state) {
  suggestionState[suggestion.id] = state
  ElMessage.success(state === 'adopted' ? '已采纳该建议，引用将记录到处理档案' : '已忽略该建议')
}

/** 提交处理记录或内部备注。 */
function submitComment() {
  const content = draft.content.trim()
  if (!content) {
    ElMessage.warning('请输入处理记录内容')
    return
  }
  ticket.value.timeline.push({
    id: Date.now(),
    kind: 'comment',
    title: draft.internal ? '内部备注' : '处理记录',
    operator: '当前登录人',
    roleLabel: '工程师',
    at: new Date(),
    content,
    internal: draft.internal
  })
  draft.content = ''
  ElMessage.success(draft.internal ? '内部备注已保存（仅内部可见）' : '处理记录已提交')
}

/** 提交报修人评价。 */
function submitRating() {
  if (!rating.comment.trim()) {
    ElMessage.warning('请填写评价内容')
    return
  }
  ticket.value.rating = { score: rating.score, comment: rating.comment.trim(), at: new Date() }
  ElMessage.success('评价已提交，感谢反馈')
}
</script>

<template>
  <div class="detail">
    <!-- 头部：单号、状态与原型状态预览 -->
    <div class="detail__head">
      <div class="detail__head-left">
        <el-button text class="back-btn" @click="backToList">
          <el-icon><ArrowLeft /></el-icon>
          返回列表
        </el-button>
        <span class="detail__no mono">{{ ticket.ticketNo }}</span>
        <StatusTag :status="previewStatus" />
        <UrgencyTag :urgency="ticket.urgency" />
      </div>

      <div class="detail__head-right">
        <PrototypeBadge />
        <span class="detail__preview-tip">原型：状态预览</span>
        <el-select v-model="previewStatus" size="small" class="detail__preview" @change="handlePreviewStatus">
          <el-option
            v-for="option in STATUS_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </div>
    </div>

    <!-- 标题与操作区 -->
    <section class="sd-card head-card">
      <div class="head-card__main">
        <h1>{{ ticket.title }}</h1>
        <div class="head-card__meta">
          <span><el-icon><User /></el-icon>{{ ticket.customer.name }}</span>
          <span><el-icon><Monitor /></el-icon>{{ ticket.device.name }}</span>
          <span><el-icon><Clock /></el-icon>创建于 {{ formatDateTime(ticket.createdAt) }}</span>
          <span><el-icon><ChatDotSquare /></el-icon>网页报修</span>
        </div>
      </div>

      <div class="head-card__actions">
        <el-button
          v-for="action in availableActions"
          :key="action.key"
          :type="action.type"
          :plain="action.type !== 'primary'"
          size="default"
          @click="runAction(action)"
        >
          {{ action.label }}
        </el-button>
        <span v-if="availableActions.length === 0" class="head-card__no-action">当前状态无可用操作</span>
      </div>
    </section>

    <div class="sd-grid-main">
      <!-- 左栏 -->
      <div class="detail__col">
        <section class="sd-card">
          <header class="sd-card__header">
            <h2 class="sd-card__title">问题描述</h2>
            <span class="sd-card__tip">{{ categoryLabel }}</span>
          </header>
          <p class="desc">{{ ticket.description }}</p>

          <div class="attachments">
            <span class="attachments__label">附件</span>
            <a
              v-for="file in ticket.attachments"
              :key="file.id"
              class="attachment"
              href="javascript:void(0)"
            >
              <el-icon><Paperclip /></el-icon>
              <span class="attachment__name">{{ file.name }}</span>
              <em class="attachment__size">{{ formatFileSize(file.size) }}</em>
            </a>
          </div>
        </section>

        <section class="sd-card">
          <header class="sd-card__header">
            <h2 class="sd-card__title">处理时间线</h2>
            <span class="sd-card__tip">{{ ticket.timeline.length }} 条记录</span>
          </header>

          <ol class="timeline">
            <li v-for="item in ticket.timeline" :key="item.id" class="timeline__item">
              <span
                class="timeline__dot"
                :class="{
                  'timeline__dot--comment': item.kind === 'comment',
                  'timeline__dot--internal': item.internal
                }"
              ></span>
              <div class="timeline__body">
                <div class="timeline__head">
                  <strong>{{ item.title }}</strong>
                  <span class="timeline__operator">{{ item.operator }} · {{ item.roleLabel }}</span>
                  <span v-if="item.internal" class="timeline__internal">内部</span>
                  <span class="timeline__time mono">{{ formatDateTime(item.at) }}</span>
                </div>
                <p class="timeline__content">{{ item.content }}</p>
              </div>
            </li>
          </ol>

          <div class="composer">
            <el-input
              v-model="draft.content"
              type="textarea"
              :rows="3"
              maxlength="2000"
              show-word-limit
              placeholder="填写处理记录，客户可在工单里看到；勾选「内部备注」后仅内部人员可见"
            />
            <div class="composer__footer">
              <el-checkbox v-model="draft.internal">内部备注</el-checkbox>
              <div class="composer__actions">
                <el-button type="primary" @click="submitComment">提交记录</el-button>
              </div>
            </div>
          </div>
        </section>
      </div>

      <!-- 右栏 -->
      <aside class="detail__side">
        <section class="sd-card side-card">
          <header class="sd-card__header">
            <h2 class="sd-card__title">客户与设备</h2>
          </header>

          <div class="entity">
            <div class="entity__head">
              <span class="entity__name">{{ ticket.customer.name }}</span>
              <span class="entity__tag">{{ levelLabel }}</span>
            </div>
            <dl class="entity__pairs">
              <div><dt>联系人</dt><dd>{{ ticket.customer.contact }}</dd></div>
              <div><dt>联系电话</dt><dd class="mono">{{ ticket.customer.phone }}</dd></div>
            </dl>
          </div>

          <div class="entity entity--device">
            <div class="entity__head">
              <span class="entity__name">{{ ticket.device.name }}</span>
              <span class="entity__tag" :class="{ 'entity__tag--warn': ticket.device.warrantyStatus === 'EXPIRED' }">
                {{ warrantyLabel }}
              </span>
            </div>
            <dl class="entity__pairs">
              <div><dt>型号</dt><dd>{{ ticket.device.model }}</dd></div>
              <div><dt>序列号</dt><dd class="mono">{{ ticket.device.sn }}</dd></div>
              <div><dt>采购日期</dt><dd>{{ ticket.device.purchasedAt }}</dd></div>
            </dl>
          </div>

          <div class="entity__assignee">
            <span class="entity__assignee-label">当前处理人</span>
            <div class="entity__assignee-main">
              <span class="sd-avatar-xs">{{ ticket.assignee.name.slice(0, 1) }}</span>
              <span>
                <strong>{{ ticket.assignee.name }}</strong>
                <em>{{ ticket.assignee.teamName }}</em>
              </span>
            </div>
          </div>
        </section>

        <section class="sd-card side-card">
          <header class="sd-card__header">
            <h2 class="sd-card__title">SLA 双时钟</h2>
          </header>

          <div class="sla-block">
            <div class="sla-block__head">
              <span>响应时钟</span>
              <span class="sla-tag sla-tag--met">已达成</span>
            </div>
            <p class="sla-block__value">
              {{ responseUsedMinutes !== null ? `${responseUsedMinutes} 分钟响应` : '已响应' }}
            </p>
            <p class="sla-block__meta">截止 {{ formatDateTime(ticket.sla.responseDeadline) }}</p>
          </div>

          <div class="sla-block" :class="{ 'sla-block--danger': !isTerminal && ticket.sla.resolveRemaining <= 30 }">
            <div class="sla-block__head">
              <span>解决时钟</span>
              <span
                class="sla-tag"
                :class="isTerminal ? 'sla-tag--met' : 'sla-tag--warning'"
              >
                {{ isTerminal ? '已停止' : '计时中' }}
              </span>
            </div>
            <p class="sla-block__value">
              {{
                isTerminal
                  ? STATUS_LABELS[previewStatus]
                  : formatMinutes(ticket.sla.resolveRemaining)
              }}
            </p>
            <el-progress
              :percentage="resolvePercent"
              :show-text="false"
              :stroke-width="6"
              :color="resolvePercent > 80 ? '#dc2626' : resolvePercent > 60 ? '#d97706' : '#2f6bff'"
            />
            <p class="sla-block__meta">截止 {{ formatDateTime(ticket.sla.resolveDeadline) }}</p>
          </div>
        </section>

        <section class="sd-card side-card">
          <header class="sd-card__header">
            <h2 class="sd-card__title">AI 知识建议</h2>
            <span class="ai-badge"><el-icon><MagicStick /></el-icon>Top5</span>
          </header>

          <ul class="suggestions">
            <li
              v-for="item in visibleSuggestions"
              :key="item.id"
              class="suggestion"
              :class="{ 'suggestion--adopted': suggestionState[item.id] === 'adopted' }"
            >
              <div class="suggestion__head">
                <strong class="suggestion__title">{{ item.title }}</strong>
                <span class="suggestion__score mono">{{ (item.similarity * 100).toFixed(0) }}%</span>
              </div>
              <p class="suggestion__snippet">{{ item.snippet }}</p>
              <p class="suggestion__source">
                <el-icon><Link /></el-icon>{{ item.source }}
              </p>

              <div v-if="suggestionState[item.id]" class="suggestion__result">
                {{ suggestionState[item.id] === 'adopted' ? '已采纳' : '已忽略' }}
              </div>
              <div v-else class="suggestion__actions">
                <el-button size="small" type="primary" plain @click="markSuggestion(item, 'adopted')">
                  采纳
                </el-button>
                <el-button size="small" plain @click="markSuggestion(item, 'ignored')">忽略</el-button>
              </div>
            </li>
          </ul>

          <el-button
            v-if="!showAllSuggestions && ticket.aiSuggestions.length > 3"
            text
            class="suggestions__more"
            @click="showAllSuggestions = true"
          >
            展开其余 {{ ticket.aiSuggestions.length - 3 }} 条
          </el-button>
        </section>

        <section v-if="previewStatus === 'CLOSED'" class="sd-card side-card">
          <header class="sd-card__header">
            <h2 class="sd-card__title">报修人评价</h2>
          </header>

          <div v-if="ticket.rating" class="rating-result">
            <el-rate :model-value="ticket.rating.score" disabled />
            <p>{{ ticket.rating.comment }}</p>
            <span class="text-muted">{{ formatDateTime(ticket.rating.at) }}</span>
          </div>

          <div v-else class="rating-form">
            <el-rate v-model="rating.score" />
            <el-input
              v-model="rating.comment"
              type="textarea"
              :rows="2"
              maxlength="200"
              placeholder="服务是否及时？问题是否彻底解决？"
            />
            <el-button type="primary" class="rating-form__submit" @click="submitRating">
              提交评价
            </el-button>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 头部 */
.detail__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.detail__head-left,
.detail__head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.back-btn {
  padding-left: 0;
  color: var(--sd-text-secondary);
}

.detail__no {
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.detail__preview-tip {
  font-size: 11px;
  color: var(--sd-text-muted);
}

.detail__preview {
  width: 118px;
}

/* 标题卡 */
.head-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.head-card__main {
  min-width: 0;
}

.head-card__main h1 {
  margin: 0;
  font-size: 18px;
  line-height: 1.5;
  font-weight: 600;
}

.head-card__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 18px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.head-card__meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.head-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex-shrink: 0;
}

.head-card__no-action {
  font-size: 12px;
  color: var(--sd-text-muted);
}

/* 左右栏 */
.detail__col,
.detail__side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

/* 描述 */
.desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.9;
  color: var(--sd-text-secondary);
  white-space: pre-wrap;
}

.attachments {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--sd-line);
}

.attachments__label {
  font-size: 12px;
  color: var(--sd-text-muted);
}

.attachment {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 7px;
  background: #f7f9fc;
  border: 1px solid var(--sd-line);
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.attachment:hover {
  border-color: var(--sd-primary);
  color: var(--sd-primary);
}

.attachment__size {
  font-style: normal;
  font-size: 10px;
  color: var(--sd-text-muted);
}

/* 时间线 */
.timeline {
  list-style: none;
  margin: 0;
  padding: 4px 0 0 4px;
}

.timeline__item {
  position: relative;
  padding: 0 0 18px 22px;
  border-left: 1px solid #e8edf5;
}

.timeline__item:last-child {
  border-left-color: transparent;
  padding-bottom: 4px;
}

.timeline__dot {
  position: absolute;
  left: -4px;
  top: 5px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sd-primary);
  box-shadow: 0 0 0 3px #eef3ff;
}

.timeline__dot--comment {
  background: #14b8a6;
  box-shadow: 0 0 0 3px #e7f8f5;
}

.timeline__dot--internal {
  background: var(--sd-orange);
  box-shadow: 0 0 0 3px var(--sd-orange-soft);
}

.timeline__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.timeline__head strong {
  font-size: 13px;
  font-weight: 600;
}

.timeline__operator {
  color: var(--sd-text-muted);
}

.timeline__internal {
  padding: 0 6px;
  height: 17px;
  line-height: 17px;
  border-radius: 4px;
  background: var(--sd-orange-soft);
  color: #b45309;
  font-size: 10px;
}

.timeline__time {
  margin-left: auto;
  font-size: 11px;
  color: var(--sd-text-muted);
}

.timeline__content {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--sd-text-secondary);
  white-space: pre-wrap;
}

/* 处理记录 */
.composer {
  margin-top: 10px;
  padding-top: 16px;
  border-top: 1px solid #f0f3f8;
}

.composer__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

/* 右栏卡片 */
.side-card {
  padding: 18px 20px;
}

.entity {
  padding: 12px 14px;
  border-radius: 10px;
  background: #f9fbfe;
  border: 1px solid #eef2f8;
}

.entity--device {
  margin-top: 10px;
}

.entity__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.entity__name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entity__tag {
  flex-shrink: 0;
  padding: 0 7px;
  height: 18px;
  line-height: 18px;
  border-radius: 4px;
  background: #eaf0ff;
  color: var(--sd-primary);
  font-size: 10px;
}

.entity__tag--warn {
  background: var(--sd-orange-soft);
  color: #b45309;
}

.entity__pairs {
  margin: 8px 0 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.entity__pairs div {
  display: flex;
  gap: 8px;
  font-size: 12px;
}

.entity__pairs dt {
  width: 58px;
  flex-shrink: 0;
  color: var(--sd-text-muted);
}

.entity__pairs dd {
  margin: 0;
  color: var(--sd-text-secondary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.entity__assignee {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--sd-line);
}

.entity__assignee-label {
  font-size: 11px;
  color: var(--sd-text-muted);
}

.entity__assignee-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.sd-avatar-xs {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #19c6b4, #0f8f86);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}

.entity__assignee-main span:last-child {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.entity__assignee-main strong {
  font-size: 13px;
  font-weight: 600;
}

.entity__assignee-main em {
  font-style: normal;
  font-size: 11px;
  color: var(--sd-text-muted);
}

/* SLA */
.sla-block {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid #eef2f8;
  background: #fbfcfe;
}

.sla-block + .sla-block {
  margin-top: 10px;
}

.sla-block--danger {
  background: #fffafa;
  border-color: #f7dede;
}

.sla-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.sla-tag {
  padding: 0 7px;
  height: 18px;
  line-height: 18px;
  border-radius: 4px;
  font-size: 10px;
  background: #eef1f6;
  color: #6b7a90;
}

.sla-tag--met {
  background: var(--sd-green-soft);
  color: var(--sd-green);
}

.sla-tag--warning {
  background: var(--sd-orange-soft);
  color: #b45309;
}

.sla-block__value {
  margin: 6px 0 4px;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.4px;
}

.sla-block--danger .sla-block__value {
  color: var(--sd-red);
}

.sla-block__meta {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--sd-text-muted);
}

/* AI 建议 */
.ai-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0 7px;
  height: 20px;
  border-radius: 999px;
  background: var(--sd-purple-soft);
  color: var(--sd-purple);
  font-size: 10px;
}

.suggestions {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.suggestion {
  padding: 11px 12px;
  border-radius: 10px;
  border: 1px solid #eef2f8;
  background: #fbfcfe;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.suggestion--adopted {
  border-color: #bbf0e4;
  background: #f7fdfb;
}

.suggestion__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.suggestion__title {
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
}

.suggestion__score {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--sd-green);
  background: var(--sd-green-soft);
  padding: 0 5px;
  border-radius: 4px;
}

.suggestion__snippet {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--sd-text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.suggestion__source {
  margin: 7px 0 0;
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--sd-text-muted);
}

.suggestion__actions {
  display: flex;
  gap: 6px;
  margin-top: 9px;
}

.suggestion__result {
  margin-top: 9px;
  font-size: 11px;
  color: var(--sd-green);
}

.suggestions__more {
  margin-top: 8px;
  font-size: 12px;
}

/* 评价 */
.rating-result p {
  margin: 8px 0 4px;
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.rating-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rating-form__submit {
  align-self: flex-end;
}
</style>
