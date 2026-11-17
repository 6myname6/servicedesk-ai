<script setup>
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="运营报表"
    icon="TrendCharts"
    description="报表页依赖团队 SLA、故障分类与 NL2SQL 问数能力，后端尚未实现，先保留入口与占位说明。"
    api-hint="R-01 运营看板、AI-04 NL2SQL（本期不做）"
    :points="['SLA 达成率', '工单量与故障 TopN', '团队 SLA 对比', 'NL2SQL 问数']"
  />
</template>
