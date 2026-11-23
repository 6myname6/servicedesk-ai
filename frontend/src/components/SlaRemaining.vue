<script setup>
import { computed } from 'vue'

import { TERMINAL_STATUSES } from '@/utils/dict'
import { formatMinutes, slaLevel } from '@/utils/format'

/**
 * SLA 剩余时间展示。
 *
 * <p>终态显示「已完成 / 已关闭」；未终态按剩余分钟着色：
 * 小于 30 分钟或已超时为红，小于 120 分钟为橙，其余为常规蓝灰。</p>
 */
const props = defineProps({
  /** 剩余分钟，负数表示已超时。 */
  minutes: { type: Number, default: null },
  /** 后端标记的是否已超时。 */
  breached: { type: Boolean, default: false },
  /** 工单状态，用于识别终态。 */
  status: { type: String, default: '' }
})

/** 是否为终态（已完成 / 不通过 / 已取消）。 */
const isTerminal = computed(() => TERMINAL_STATUSES.includes(props.status))

/** 终态文案。 */
const terminalText = computed(() => {
  if (props.status === 'CLOSED') {
    return '已完成'
  }
  if (props.status === 'REJECTED') {
    return '不通过'
  }
  return '已取消'
})

/** 终态颜色：已完成绿、不通过红、已取消灰。 */
const terminalClass = computed(() => {
  if (props.status === 'CLOSED') {
    return 'sd-sla--done'
  }
  if (props.status === 'REJECTED') {
    return 'sd-sla--danger'
  }
  return 'sd-sla--muted'
})

/** 非终态下的展示文本与颜色等级。 */
const text = computed(() => formatMinutes(props.minutes))

/** 着色等级。 */
const level = computed(() => slaLevel(props.minutes, props.breached))
</script>

<template>
  <span v-if="isTerminal" class="sd-sla" :class="terminalClass">{{ terminalText }}</span>
  <span v-else class="sd-sla" :class="`sd-sla--${level}`">{{ text }}</span>
</template>

<style scoped>
.sd-sla {
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  color: #2563eb;
}

.sd-sla--warning {
  color: var(--sd-orange);
}

.sd-sla--danger {
  color: var(--sd-red);
}

.sd-sla--done {
  color: var(--sd-green);
}

.sd-sla--muted {
  color: #94a3b8;
}
</style>
