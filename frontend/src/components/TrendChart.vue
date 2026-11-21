<script setup>
import { computed } from 'vue'

/**
 * 趋势折线图（纯 SVG，不引入图表依赖）。
 *
 * <p>用于工作台与报表页的原型展示：支持「新建 / 解决」两条序列，
 * 传入数据变化时自动重算坐标与刻度。</p>
 */
const props = defineProps({
  /** 数据点数组，元素形如 { label, created, resolved }。 */
  data: { type: Array, default: () => [] },
  /** 画布高度（viewBox 内的高度）。 */
  height: { type: Number, default: 240 }
})

/** 画布宽度（viewBox 内），配合宽度 100% 等比缩放。 */
const WIDTH = 720

/** 绘图内边距，左侧留给 Y 轴刻度、底部留给 X 轴标签。 */
const PADDING = { top: 18, right: 20, bottom: 32, left: 44 }

/** 两条序列的展示定义，颜色取自设计令牌。 */
const SERIES = [
  { key: 'created', name: '新建工单', color: '#2f6bff' },
  { key: 'resolved', name: '解决工单', color: '#14b8a6' }
]

/** 绘图区宽度与高度。 */
const innerWidth = WIDTH - PADDING.left - PADDING.right
const innerHeight = computed(() => props.height - PADDING.top - PADDING.bottom)

/** Y 轴最大值：按 4 段取整，避免刻度出现小数。 */
const maxValue = computed(() => {
  const values = props.data.flatMap((item) => [Number(item.created) || 0, Number(item.resolved) || 0])
  const raw = Math.max(...values, 1)
  const step = raw <= 8 ? 1 : raw <= 20 ? 5 : Math.ceil(raw / 40) * 10
  return Math.max(step * 4, raw)
})

/**
 * 第 index 个数据点的 X 坐标。
 *
 * @param {number} index 数据点下标
 * @returns {number} 画布内 X 坐标
 */
function xAt(index) {
  const count = props.data.length
  if (count <= 1) {
    return PADDING.left + innerWidth / 2
  }
  return PADDING.left + (innerWidth / (count - 1)) * index
}

/**
 * 数值对应的 Y 坐标（数值越大越靠上）。
 *
 * @param {number} value 数值
 * @returns {number} 画布内 Y 坐标
 */
function yAt(value) {
  const ratio = maxValue.value === 0 ? 0 : value / maxValue.value
  return PADDING.top + innerHeight.value * (1 - ratio)
}

/**
 * 把某条序列转成 polyline 的 points 字符串。
 *
 * @param {string} key 序列字段名 created / resolved
 * @returns {string} "x,y x,y" 形式
 */
function linePoints(key) {
  return props.data
    .map((item, index) => `${xAt(index).toFixed(1)},${yAt(Number(item[key]) || 0).toFixed(1)}`)
    .join(' ')
}

/**
 * 生成「新建工单」序列的闭合面积路径，用于渐变填充。
 *
 * @returns {string} SVG path d 属性
 */
function areaPath() {
  if (props.data.length === 0) {
    return ''
  }
  const points = props.data.map(
    (item, index) => `${xAt(index).toFixed(1)},${yAt(Number(item.created) || 0).toFixed(1)}`
  )
  const baseline = yAt(0).toFixed(1)
  return `M ${PADDING.left},${baseline} L ${points.join(' L ')} L ${xAt(
    props.data.length - 1
  ).toFixed(1)},${baseline} Z`
}

/** Y 轴刻度文案：上到下 4 段。 */
const yTicks = computed(() =>
  [1, 0.75, 0.5, 0.25, 0].map((ratio) => ({
    y: PADDING.top + innerHeight.value * (1 - ratio),
    value: Math.round(maxValue.value * ratio)
  }))
)

/** 每个数据点的圆点坐标，含原始数值。 */
const dots = computed(() =>
  props.data.flatMap((item, index) =>
    SERIES.map((series) => ({
      key: `${series.key}-${index}`,
      cx: xAt(index),
      cy: yAt(Number(item[series.key]) || 0),
      color: series.color,
      value: item[series.key]
    }))
  )
)
</script>

<template>
  <div class="trend">
    <div class="trend__legend">
      <span v-for="series in SERIES" :key="series.key" class="trend__legend-item">
        <i class="trend__dot" :style="{ background: series.color }"></i>
        {{ series.name }}
      </span>
    </div>

    <svg
      class="trend__canvas"
      :viewBox="`0 0 ${WIDTH} ${height}`"
      preserveAspectRatio="xMidYMid meet"
      role="img"
      aria-label="工单量趋势"
    >
      <defs>
        <linearGradient id="trendAreaFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#2f6bff" stop-opacity="0.18" />
          <stop offset="100%" stop-color="#2f6bff" stop-opacity="0" />
        </linearGradient>
      </defs>

      <!-- 网格与 Y 轴刻度 -->
      <g>
        <line
          v-for="tick in yTicks"
          :key="`grid-${tick.y}`"
          :x1="PADDING.left"
          :x2="WIDTH - PADDING.right"
          :y1="tick.y"
          :y2="tick.y"
          stroke="#eef1f7"
          stroke-width="1"
        />
        <text
          v-for="tick in yTicks"
          :key="`label-${tick.y}`"
          :x="PADDING.left - 8"
          :y="tick.y + 4"
          text-anchor="end"
          class="trend__axis-text"
        >
          {{ tick.value }}
        </text>
      </g>

      <!-- 面积与折线 -->
      <path v-if="data.length" :d="areaPath()" fill="url(#trendAreaFill)" />
      <polyline
        v-for="series in SERIES"
        :key="series.key"
        :points="linePoints(series.key)"
        fill="none"
        :stroke="series.color"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />

      <!-- 数据点 -->
      <circle
        v-for="dot in dots"
        :key="dot.key"
        :cx="dot.cx"
        :cy="dot.cy"
        r="3"
        fill="#fff"
        :stroke="dot.color"
        stroke-width="2"
      >
        <title>{{ dot.value }}</title>
      </circle>

      <!-- X 轴标签 -->
      <text
        v-for="(item, index) in data"
        :key="`x-${item.label}`"
        :x="xAt(index)"
        :y="height - 10"
        text-anchor="middle"
        class="trend__axis-text"
      >
        {{ item.label }}
      </text>
    </svg>

    <p v-if="data.length === 0" class="trend__empty">暂无趋势数据</p>
  </div>
</template>

<style scoped>
.trend {
  width: 100%;
}

.trend__legend {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 4px;
  font-size: 11px;
  color: var(--sd-text-secondary);
}

.trend__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.trend__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}

.trend__canvas {
  width: 100%;
  height: auto;
  display: block;
}

.trend__axis-text {
  font-size: 10px;
  fill: #98a6ba;
  font-family: inherit;
}

.trend__empty {
  margin: 0;
  padding: 32px 0;
  text-align: center;
  color: var(--sd-text-muted);
  font-size: 12px;
}
</style>
