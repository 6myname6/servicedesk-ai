<script setup>
import { computed } from 'vue'

/** 紧急度标签：P0 红、P1 橙、P2 蓝、P3 灰，与设计稿一致。 */
const props = defineProps({
  /** 紧急度编码 P0-P3。 */
  urgency: { type: String, required: true }
})

/** 样式类后缀，P0 → p0。 */
const variant = computed(() => String(props.urgency || '').toLowerCase())
</script>

<template>
  <span class="sd-urgency mono" :class="`sd-urgency--${variant}`">{{ urgency }}</span>
</template>

<style scoped>
.sd-urgency {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 600;
  background: #f2f5fa;
  color: #64748b;
}

.sd-urgency--p0 {
  background: var(--sd-red-soft);
  color: var(--sd-red);
}

.sd-urgency--p1 {
  background: var(--sd-orange-soft);
  color: var(--sd-orange);
}

.sd-urgency--p2 {
  background: #e8f0ff;
  color: #2563eb;
}

.sd-urgency--p3 {
  background: #eef1f6;
  color: #64748b;
}
</style>
