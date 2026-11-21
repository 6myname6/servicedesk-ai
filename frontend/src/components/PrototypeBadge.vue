<script setup>
/**
 * 原型数据标识。
 *
 * <p>挂在仍使用 mock 数据的页面上，提醒评审者当前区块不是真实接口返回值；
 * 接口接通后随 mock 数据一并移除。</p>
 */
defineProps({
  /** 徽标文案。 */
  text: { type: String, default: '原型示例数据' }
})
</script>

<template>
  <span class="proto-badge" title="该区块为页面原型示例数据，尚未接入后端接口">
    <el-icon><InfoFilled /></el-icon>
    {{ text }}
  </span>
</template>

<style scoped>
.proto-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: #f5f7fb;
  border: 1px dashed #d7dfec;
  color: var(--sd-text-muted);
  font-size: 10px;
  white-space: nowrap;
}
</style>
