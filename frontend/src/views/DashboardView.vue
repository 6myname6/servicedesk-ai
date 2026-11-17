<script setup>
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="工作台"
    icon="Odometer"
    description="工作台需要按角色聚合待办、SLA 告警与近 7 天趋势，后端统计接口尚未提供，因此暂以占位页展示。"
    api-hint="R-01 运营看板、工单统计聚合（W3/W4 排期）"
    :points="['我的待办', 'SLA 告警列表', '今日解决与达成率', '近 7 天工单趋势']"
  />
</template>
