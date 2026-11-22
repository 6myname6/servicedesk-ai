<script setup>
import { computed } from 'vue'

import { STATUS_LABELS } from '@/utils/dict'

/** 工单状态标签，颜色按状态语义固定（非终态用暖色/蓝色，终态用绿/灰）。 */
const props = defineProps({
  /** 状态编码，见开发文档 12.1。 */
  status: { type: String, required: true }
})

/** 中文文案，未知状态原样显示。 */
const label = computed(() => STATUS_LABELS[props.status] || props.status)

/** 状态对应的样式类后缀。 */
const variant = computed(() => String(props.status || '').toLowerCase())
</script>

<template>
  <span class="sd-status" :class="`sd-status--${variant}`">{{ label }}</span>
</template>

<style scoped>
.sd-status {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  background: #f2f5fa;
  color: var(--sd-text-secondary);
}

.sd-status--new {
  background: var(--sd-orange-soft);
  color: #b45309;
}

.sd-status--assigned {
  background: #e8f0ff;
  color: #1d4ed8;
}

.sd-status--accepted {
  background: #e8f0ff;
  color: #2563eb;
}

.sd-status--in_progress {
  background: #fff1e0;
  color: #b45309;
}

.sd-status--waiting_customer {
  background: var(--sd-purple-soft);
  color: #6d28d9;
}

.sd-status--resolved {
  background: var(--sd-green-soft);
  color: var(--sd-green);
}

.sd-status--closed {
  background: #eef1f6;
  color: #6b7a90;
}

.sd-status--reopened {
  background: var(--sd-red-soft);
  color: var(--sd-red);
}

.sd-status--cancelled {
  background: #eef1f6;
  color: #94a3b8;
}
</style>
