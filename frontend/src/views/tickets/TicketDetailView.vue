<script setup>
import { useRoute, useRouter } from 'vue-router'

import PagePlaceholder from '@/components/PagePlaceholder.vue'

const route = useRoute()
const router = useRouter()

/** 返回工单列表。 */
function backToList() {
  router.push('/tickets')
}
</script>

<template>
  <PagePlaceholder
    title="工单详情"
    icon="Document"
    :description="`工单 ID ${route.params.id} 的详情、时间线与操作区需要详情接口（T-03）与时间线接口（F-04），后端尚未实现。`"
    api-hint="GET /api/tickets/{id}、F-01 派单、F-02 状态流转、F-03 处理记录"
    :points="['描述与附件', '时间线（含内部备注）', '响应 / 解决双倒计时', 'AI 建议与引用', '派单与状态操作']"
  >
    <el-button @click="backToList">返回工单列表</el-button>
  </PagePlaceholder>
</template>
