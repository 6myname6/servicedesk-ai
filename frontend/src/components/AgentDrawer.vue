<script setup>
import { nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { demoAgentReply, demoAgentSeed } from '@/mock/prototype'
import { formatShortDateTime } from '@/utils/format'

/** 快捷提问，仅在会话为空或需要引导时展示。 */
const QUICK_QUESTIONS = ['查一下 TK20261121000042 的进度', 'DS2208 扫码枪连不上 MES 怎么处理？', '帮我创建一张扫码枪故障工单']

/** 初始化会话内容。 */
const seed = demoAgentSeed()

/** 抽屉是否打开。 */
const visible = ref(false)

/** 输入框内容。 */
const draft = ref('')

/** 助手回复中，禁止重复发送。 */
const sending = ref(false)

/** 会话消息。 */
const messages = ref(seed.messages)

/** 本次 run 的工具步骤。 */
const steps = ref(seed.steps)

/** 步骤面板是否展开。 */
const stepsOpen = ref(true)

/** 待确认的写操作；不为空时展示确认卡片。 */
const pendingConfirm = ref(null)

/** 消息滚动容器。 */
const bodyRef = ref(null)

/** 消息自增 ID。 */
let messageId = 1

/** 滚动到消息底部。 */
async function scrollToBottom() {
  await nextTick()
  const el = bodyRef.value
  if (el) {
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  }
}

/**
 * 追加一条消息。
 *
 * @param {'user'|'assistant'} role 角色
 * @param {string} content 内容
 */
function pushMessage(role, content) {
  messageId += 1
  messages.value.push({ id: messageId, role, content, at: new Date() })
}

/**
 * 发送消息并生成模拟回复。
 *
 * @param {string} [text] 指定文本（快捷提问），为空时取输入框
 */
async function send(text) {
  const content = (text ?? draft.value).trim()
  if (!content || sending.value) {
    return
  }

  pushMessage('user', content)
  draft.value = ''
  sending.value = true
  await scrollToBottom()

  // 原型：模拟一次 Agent 往返（真实实现见接口文档 AI-03）
  await new Promise((resolve) => window.setTimeout(resolve, 700))
  const result = demoAgentReply(content)
  pushMessage('assistant', result.reply.content)
  steps.value = [...steps.value.filter((item) => item.status !== 'waiting'), ...result.steps]
  pendingConfirm.value = result.confirm
  sending.value = false
  await scrollToBottom()
}

/** 确认执行写操作。 */
async function confirmWrite() {
  const current = pendingConfirm.value
  if (!current) {
    return
  }
  pendingConfirm.value = null
  steps.value = steps.value.map((item) =>
    item.status === 'waiting'
      ? { ...item, status: 'success', cost: '1.1s', label: '已创建工单 TK20261121000057' }
      : item
  )
  pushMessage(
    'assistant',
    '已创建工单 TK20261121000057，状态「待派单」，紧急度 P1，已通知华东一线支持组。' +
      '你可以点击工单号查看详情，或继续让我查询其他内容。'
  )
  ElMessage.success('工单已创建')
  await scrollToBottom()
}

/** 取消写操作，不做任何变更。 */
async function cancelWrite() {
  pendingConfirm.value = null
  steps.value = steps.value.filter((item) => item.status !== 'waiting')
  pushMessage('assistant', '已取消本次写操作，系统没有任何变更。需要调整信息后再告诉我。')
  await scrollToBottom()
}

/** 打开抽屉并滚到底部。 */
function open() {
  visible.value = true
  scrollToBottom()
}

/**
 * 输入框回车发送：Shift+Enter 换行。
 *
 * @param {KeyboardEvent} event 键盘事件
 */
function handleEnter(event) {
  if (event.shiftKey) {
    return
  }
  event.preventDefault()
  send()
}
</script>

<template>
  <div class="agent">
    <button type="button" class="agent__fab" title="打开 AI 坐席" @click="open">
      <el-icon><ChatDotRound /></el-icon>
      <span class="agent__fab-dot"></span>
    </button>

    <el-drawer
      v-model="visible"
      direction="rtl"
      size="440px"
      :with-header="false"
      class="agent-drawer"
    >
      <div class="agent__panel">
        <header class="agent__head">
          <span class="agent__avatar">
            <el-icon><MagicStick /></el-icon>
          </span>
          <div class="agent__head-text">
            <strong>AI 坐席</strong>
            <em><i class="agent__online"></i>在线 · 只读操作直接执行，写操作需确认</em>
          </div>
          <el-button text class="agent__close" @click="visible = false">
            <el-icon><Close /></el-icon>
          </el-button>
        </header>

        <div ref="bodyRef" class="agent__body">
          <div v-for="message in messages" :key="message.id" class="msg" :class="`msg--${message.role}`">
            <span v-if="message.role === 'assistant'" class="msg__avatar">
              <el-icon><MagicStick /></el-icon>
            </span>
            <div class="msg__main">
              <p class="msg__bubble">{{ message.content }}</p>
              <span class="msg__time mono">{{ formatShortDateTime(message.at) }}</span>
            </div>
          </div>

          <div v-if="sending" class="msg msg--assistant">
            <span class="msg__avatar"><el-icon><MagicStick /></el-icon></span>
            <div class="msg__main">
              <p class="msg__bubble msg__bubble--typing">
                <i></i><i></i><i></i>
              </p>
            </div>
          </div>

          <!-- 工具步骤 -->
          <div v-if="steps.length" class="steps">
            <button type="button" class="steps__toggle" @click="stepsOpen = !stepsOpen">
              <el-icon><Operation /></el-icon>
              本次执行 {{ steps.length }} 个工具步骤
              <el-icon class="steps__caret">
                <component :is="stepsOpen ? 'ArrowUp' : 'ArrowDown'" />
              </el-icon>
            </button>
            <ul v-show="stepsOpen" class="steps__list">
              <li v-for="(step, index) in steps" :key="`${step.name}-${index}`" class="step">
                <span class="step__status" :class="`step__status--${step.status}`"></span>
                <span class="step__main">
                  <span class="step__name mono">{{ step.name }}</span>
                  <span class="step__label">{{ step.label }}</span>
                </span>
                <span class="step__cost mono">{{ step.cost }}</span>
              </li>
            </ul>
          </div>

          <!-- 写操作确认卡片 -->
          <div v-if="pendingConfirm" class="confirm">
            <span class="confirm__badge">
              <el-icon><WarningFilled /></el-icon>
              写操作待确认
            </span>
            <p class="confirm__title">
              工具 <code class="mono">{{ pendingConfirm.tool }}</code>
            </p>
            <p class="confirm__target">影响对象：{{ pendingConfirm.target }}</p>
            <dl class="confirm__params">
              <div v-for="param in pendingConfirm.params" :key="param.key">
                <dt class="mono">{{ param.key }}</dt>
                <dd>{{ param.value }}</dd>
              </div>
            </dl>
            <div class="confirm__actions">
              <el-button size="small" @click="cancelWrite">取消</el-button>
              <el-button size="small" type="primary" @click="confirmWrite">确认执行</el-button>
            </div>
          </div>

          <div v-if="messages.length <= 1" class="quick">
            <span class="quick__label">可以这样问</span>
            <button
              v-for="item in QUICK_QUESTIONS"
              :key="item"
              type="button"
              class="quick__item"
              @click="send(item)"
            >
              {{ item }}
            </button>
          </div>
        </div>

        <footer class="agent__footer">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="500"
            placeholder="描述你的问题，Enter 发送，Shift+Enter 换行"
            @keydown.enter="handleEnter"
          />
          <div class="agent__footer-bar">
            <span class="agent__hint">AI 生成内容仅供参考，以工单记录为准</span>
            <el-button type="primary" size="small" :loading="sending" @click="send()">发送</el-button>
          </div>
        </footer>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 浮动按钮 */
.agent__fab {
  position: fixed;
  right: 26px;
  bottom: 32px;
  width: 54px;
  height: 54px;
  border: none;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #19c6b4, #0f8f86);
  color: #fff;
  font-size: 24px;
  cursor: pointer;
  box-shadow: 0 10px 24px rgba(15, 143, 134, 0.32);
  z-index: 1500;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}

.agent__fab:hover {
  transform: translateY(-2px);
  box-shadow: 0 14px 28px rgba(15, 143, 134, 0.4);
}

.agent__fab-dot {
  position: absolute;
  right: 6px;
  top: 6px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #ff4d4f;
  border: 2px solid #fff;
}

/* 面板 */
.agent__panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.agent__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--sd-line);
}

.agent__avatar {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #19c6b4, #0f8f86);
  color: #fff;
  font-size: 17px;
}

.agent__head-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.agent__head-text strong {
  font-size: 14px;
}

.agent__head-text em {
  font-style: normal;
  font-size: 10px;
  color: var(--sd-text-muted);
  display: flex;
  align-items: center;
  gap: 4px;
}

.agent__online {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
  display: inline-block;
}

.agent__close {
  color: var(--sd-text-muted);
}

/* 消息区 */
.agent__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 2px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.msg {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.msg--user {
  flex-direction: row-reverse;
}

.msg__avatar {
  width: 26px;
  height: 26px;
  border-radius: 9px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #eaf7f5;
  color: #0f8f86;
  font-size: 14px;
}

.msg__main {
  display: flex;
  flex-direction: column;
  max-width: 82%;
}

.msg--user .msg__main {
  align-items: flex-end;
}

.msg__bubble {
  margin: 0;
  padding: 9px 12px;
  border-radius: 2px 12px 12px 12px;
  background: #f4f7fb;
  color: var(--sd-text);
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg--user .msg__bubble {
  border-radius: 12px 2px 12px 12px;
  background: var(--sd-primary);
  color: #fff;
}

.msg__time {
  margin-top: 3px;
  font-size: 10px;
  color: var(--sd-text-muted);
}

/* 输入中 */
.msg__bubble--typing {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 12px 14px;
}

.msg__bubble--typing i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #9fb0c6;
  animation: blink 1.2s infinite ease-in-out;
}

.msg__bubble--typing i:nth-child(2) {
  animation-delay: 0.2s;
}

.msg__bubble--typing i:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.3;
  }
  40% {
    opacity: 1;
  }
}

/* 工具步骤 */
.steps {
  border: 1px solid var(--sd-line);
  border-radius: 10px;
  overflow: hidden;
  background: #fbfcfe;
}

.steps__toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: var(--sd-text-secondary);
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
}

.steps__caret {
  margin-left: auto;
  font-size: 11px;
}

.steps__list {
  list-style: none;
  margin: 0;
  padding: 2px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 11px;
}

.step__status {
  width: 6px;
  height: 6px;
  margin-top: 5px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--sd-green);
}

.step__status--waiting {
  background: var(--sd-orange);
}

.step__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.step__name {
  font-size: 10px;
  color: var(--sd-primary);
}

.step__label {
  color: var(--sd-text-secondary);
  line-height: 1.6;
}

.step__cost {
  color: var(--sd-text-muted);
  font-size: 10px;
}

/* 确认卡片 */
.confirm {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid #f5dfb5;
  background: #fffcf5;
}

.confirm__badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: #b45309;
  background: var(--sd-orange-soft);
  padding: 0 7px;
  height: 19px;
  border-radius: 999px;
}

.confirm__title {
  margin: 8px 0 0;
  font-size: 12px;
}

.confirm__title code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f2f5fa;
  font-size: 11px;
  color: var(--sd-text);
}

.confirm__target {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--sd-text-secondary);
}

.confirm__params {
  margin: 8px 0 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.confirm__params div {
  display: flex;
  gap: 8px;
  font-size: 11px;
}

.confirm__params dt {
  width: 62px;
  color: var(--sd-text-muted);
}

.confirm__params dd {
  margin: 0;
  color: var(--sd-text);
}

.confirm__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

/* 快捷问题 */
.quick {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 2px;
}

.quick__label {
  font-size: 10px;
  color: var(--sd-text-muted);
}

.quick__item {
  text-align: left;
  padding: 7px 11px;
  border-radius: 8px;
  border: 1px dashed #d7dfec;
  background: #fff;
  color: var(--sd-text-secondary);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease;
}

.quick__item:hover {
  border-color: var(--sd-primary);
  color: var(--sd-primary);
}

/* 底部输入 */
.agent__footer {
  padding-top: 12px;
  border-top: 1px solid var(--sd-line);
}

.agent__footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  gap: 10px;
}

.agent__hint {
  font-size: 10px;
  color: var(--sd-text-muted);
}
</style>

<style>
/* 抽屉是 teleport 到 body 的，需用全局样式控制内边距 */
.agent-drawer .el-drawer__body {
  padding: 16px 20px;
  overflow: hidden;
}
</style>
