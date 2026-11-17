<script setup>
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="知识库"
    icon="Collection"
    description="知识文章的增删改查、索引状态与向量检索依赖后端知识库模块，尚未实现，先保留入口。"
    api-hint="K-01 知识文章管理、K-02 索引与向量检索、AI-02 知识推荐"
    :points="['文章列表与状态', 'Markdown 正文编辑', '重建索引', '命中引用回溯']"
  />
</template>
