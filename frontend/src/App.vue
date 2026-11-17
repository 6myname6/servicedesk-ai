<script setup>
import { onBeforeUnmount, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 全局根组件：只负责挂路由出口，并集中处理鉴权失效事件。 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * 401 统一出口。
 *
 * <p>HTTP 层不直接引用 store 或 router，避免循环依赖，改为派发事件由根组件收口：
 * 清空登录态后带 redirect 跳登录页。</p>
 */
function handleUnauthorized() {
  if (route.path === '/login') {
    return
  }
  auth.clear()
  router.replace({ path: '/login', query: { redirect: route.fullPath } })
}

onMounted(() => {
  window.addEventListener('sd:unauthorized', handleUnauthorized)
})

onBeforeUnmount(() => {
  window.removeEventListener('sd:unauthorized', handleUnauthorized)
})
</script>

<template>
  <router-view />
</template>
