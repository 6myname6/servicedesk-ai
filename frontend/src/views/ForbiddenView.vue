<script setup>
import { useRouter } from 'vue-router'

/** 403 页面：角色不足时的统一落地页。 */
const router = useRouter()

/** 返回工作台。 */
function goHome() {
  router.replace('/dashboard')
}
</script>

<template>
  <div class="sd-card forbidden">
    <el-icon class="forbidden__icon"><Lock /></el-icon>
    <h2>没有访问权限</h2>
    <p>当前账号的角色不允许进入该页面，如需访问请联系管理员调整权限。</p>
    <el-button type="primary" @click="goHome">返回工作台</el-button>
  </div>
</template>

<style scoped>
.forbidden {
  max-width: 520px;
  margin: 64px auto;
  text-align: center;
  padding: 40px 32px;
}

.forbidden__icon {
  font-size: 34px;
  color: var(--sd-text-muted);
  margin-bottom: 12px;
}

.forbidden h2 {
  margin: 0 0 8px;
  font-size: 17px;
}

.forbidden p {
  margin: 0 0 20px;
  color: var(--sd-text-secondary);
}
</style>
