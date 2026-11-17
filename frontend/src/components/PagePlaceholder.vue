<script setup>
/**
 * 未实现页面的统一占位组件。
 *
 * <p>前端进度与后端对齐：后端尚未提供的页面统一展示「功能实现中ing，敬请期待~」，
 * 并把依赖的接口编号写在下方，便于后续补齐时对照。</p>
 */
defineProps({
  /** 页面标题。 */
  title: { type: String, required: true },
  /** 页面用途说明。 */
  description: { type: String, default: '' },
  /** 后端尚未提供的接口或能力说明。 */
  apiHint: { type: String, default: '' },
  /** 计划提供的内容点。 */
  points: { type: Array, default: () => [] },
  /** 占位图标名。 */
  icon: { type: String, default: 'Clock' }
})
</script>

<template>
  <section class="sd-card placeholder">
    <span class="placeholder__badge">
      <el-icon><Tools /></el-icon>
      开发中
    </span>

    <span class="placeholder__icon">
      <el-icon><component :is="icon" /></el-icon>
    </span>

    <h1>{{ title }}</h1>
    <p class="placeholder__headline">功能实现中ing，敬请期待~</p>
    <p v-if="description" class="placeholder__desc">{{ description }}</p>

    <div v-if="points.length" class="placeholder__points">
      <span v-for="point in points" :key="point" class="placeholder__point">{{ point }}</span>
    </div>

    <p v-if="apiHint" class="placeholder__api">依赖能力：{{ apiHint }}</p>

    <div class="placeholder__actions">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.placeholder {
  min-height: 460px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 56px 32px;
  background:
    radial-gradient(circle at 50% 0%, rgba(47, 107, 255, 0.06), transparent 60%),
    var(--sd-card);
  border: 1px dashed #dbe3f0;
  box-shadow: none;
}

.placeholder__badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--sd-primary-soft);
  color: var(--sd-primary);
  font-size: 11px;
  margin-bottom: 18px;
}

.placeholder__icon {
  width: 60px;
  height: 60px;
  border-radius: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  color: var(--sd-primary);
  background: #f2f6ff;
  border: 1px solid #e2eaff;
  margin-bottom: 18px;
}

.placeholder h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
}

.placeholder__headline {
  margin: 10px 0 0;
  font-size: 14px;
  color: var(--sd-primary);
  font-weight: 500;
  letter-spacing: 0.3px;
}

.placeholder__desc {
  margin: 10px 0 0;
  max-width: 520px;
  color: var(--sd-text-secondary);
  font-size: 13px;
}

.placeholder__points {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 20px;
  max-width: 620px;
}

.placeholder__point {
  padding: 4px 12px;
  border-radius: 999px;
  background: #f5f7fb;
  border: 1px solid var(--sd-line);
  color: var(--sd-text-secondary);
  font-size: 12px;
}

.placeholder__api {
  margin-top: 22px;
  font-size: 11px;
  color: var(--sd-text-muted);
}

.placeholder__actions {
  margin-top: 18px;
  display: flex;
  gap: 10px;
}
</style>
