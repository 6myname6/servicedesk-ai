<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

/** 演示账号，密码为种子数据统一口令；仅用于本地演示环境。 */
const DEMO_ACCOUNTS = [
  { username: 'admin', label: '管理员' },
  { username: 'sup1', label: '主管' },
  { username: 'eng1', label: '工程师' },
  { username: 'rep1', label: '报修人' }
]

/** 演示账号统一密码，与 backend/sql/seed/seed_demo_data.sql 保持一致。 */
const DEMO_PASSWORD = 'Sd123456'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref(null)
const loading = ref(false)
const errorMessage = ref('')

const form = reactive({
  username: '',
  password: ''
})

/** 表单校验规则：只校验必填，长度口径交给后端，避免前后端提示不一致。 */
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/**
 * 提交登录。
 *
 * <p>成功后按产品文档 P-01 的跳转规则落地：管理员/主管进工作台，
 * 工程师与报修人进工单列表；若来自被拦截的页面则优先回原页面。</p>
 */
async function submit() {
  if (loading.value) {
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  loading.value = true
  errorMessage.value = ''
  try {
    const user = await auth.login({ username: form.username.trim(), password: form.password })
    const redirect = route.query.redirect
    const fallback = ['ADMIN', 'SUPERVISOR'].includes(user.role) ? '/dashboard' : '/tickets'
    router.replace(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : fallback)
  } catch (error) {
    errorMessage.value = error.code === 401 ? '用户名或密码错误' : error.message || '服务暂不可用'
  } finally {
    loading.value = false
  }
}

/**
 * 点击演示账号时回填表单。
 *
 * @param {string} username 账号
 */
function fillDemo(username) {
  form.username = username
  form.password = DEMO_PASSWORD
  errorMessage.value = ''
}
</script>

<template>
  <div class="login">
    <section class="login__brand">
      <div class="login__brand-top">
        <span class="login__logo">
          <el-icon><Service /></el-icon>
        </span>
        <span class="login__logo-text">
          <strong>智维工单</strong>
          <em>ServiceDesk AI</em>
        </span>
      </div>

      <div class="login__brand-main">
        <h1>让每一张工单<br />都跑到闭环</h1>
        <p>报修、派单、处理、知识沉淀与 SLA 考核跑在一条线上，AI 只做增强，不替代人。</p>

        <ul class="login__features">
          <li>
            <span class="login__feature-icon"><el-icon><Tickets /></el-icon></span>
            <span>
              <strong>工单全生命周期</strong>
              <em>9 种状态流转，时间线可追溯</em>
            </span>
          </li>
          <li>
            <span class="login__feature-icon"><el-icon><Timer /></el-icon></span>
            <span>
              <strong>SLA 双时钟</strong>
              <em>响应与解决分别计时，超时红字提醒</em>
            </span>
          </li>
          <li>
            <span class="login__feature-icon"><el-icon><Cpu /></el-icon></span>
            <span>
              <strong>AI 辅助</strong>
              <em>分类定级、知识推荐、运营问数</em>
            </span>
          </li>
        </ul>
      </div>

      <p class="login__brand-footer">企业售后 / 设备报修工单系统 · MVP</p>
    </section>

    <section class="login__panel">
      <div class="login__form-wrap">
        <h2>登录</h2>
        <p class="login__subtitle">使用企业账号进入服务台</p>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          class="login__alert"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              size="large"
              placeholder="请输入用户名"
              autocomplete="username"
              :prefix-icon="User"
              @keyup.enter="submit"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              placeholder="请输入密码"
              autocomplete="current-password"
              show-password
              :prefix-icon="Lock"
              @keyup.enter="submit"
            />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="login__submit"
            :loading="loading"
            @click="submit"
          >
            {{ loading ? '登录中…' : '登录' }}
          </el-button>
        </el-form>

        <div class="login__demo">
          <div class="login__demo-head">
            <span>演示账号</span>
            <span class="login__demo-pwd mono">统一密码 {{ DEMO_PASSWORD }}</span>
          </div>
          <div class="login__demo-list">
            <button
              v-for="account in DEMO_ACCOUNTS"
              :key="account.username"
              type="button"
              class="login__demo-item"
              @click="fillDemo(account.username)"
            >
              <strong>{{ account.username }}</strong>
              <em>{{ account.label }}</em>
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  background: #fff;
}

/* 左侧品牌区 */
.login__brand {
  position: relative;
  flex: 1 1 56%;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 44px 56px;
  color: #dce7f2;
  background:
    radial-gradient(900px 420px at 8% 4%, rgba(20, 184, 166, 0.22), transparent 62%),
    radial-gradient(760px 520px at 92% 96%, rgba(47, 107, 255, 0.3), transparent 64%),
    var(--sd-navy);
  overflow: hidden;
}

.login__brand::after {
  content: '';
  position: absolute;
  right: -120px;
  top: 120px;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 0 0 70px rgba(255, 255, 255, 0.03);
  pointer-events: none;
}

.login__brand-top {
  display: flex;
  align-items: center;
  gap: 12px;
  position: relative;
  z-index: 1;
}

.login__logo {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #19c6b4, #0e9c8e);
  color: #fff;
  font-size: 20px;
}

.login__logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.login__logo-text strong {
  color: #fff;
  font-size: 17px;
  letter-spacing: 1.5px;
}

.login__logo-text em {
  font-style: normal;
  font-size: 10px;
  color: #8ba3b9;
  letter-spacing: 0.5px;
}

.login__brand-main {
  position: relative;
  z-index: 1;
  max-width: 520px;
  margin: 32px 0;
}

.login__brand-main h1 {
  margin: 0;
  font-size: 40px;
  line-height: 1.3;
  letter-spacing: 2px;
  color: #fff;
  font-weight: 600;
}

.login__brand-main > p {
  margin: 18px 0 32px;
  font-size: 13px;
  line-height: 1.9;
  color: #9fb4c8;
  max-width: 440px;
}

.login__features {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login__features li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.login__feature-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #5eead4;
  font-size: 16px;
}

.login__features strong {
  display: block;
  color: #eaf3fb;
  font-size: 13px;
  font-weight: 500;
}

.login__features em {
  font-style: normal;
  font-size: 12px;
  color: #8ba3b9;
}

.login__brand-footer {
  position: relative;
  z-index: 1;
  margin: 0;
  font-size: 11px;
  color: #6f889f;
}

/* 右侧表单区 */
.login__panel {
  flex: 1 1 44%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}

.login__form-wrap {
  width: 100%;
  max-width: 360px;
}

.login__form-wrap h2 {
  margin: 0;
  font-size: 24px;
  letter-spacing: 1px;
}

.login__subtitle {
  margin: 8px 0 26px;
  color: var(--sd-text-muted);
  font-size: 13px;
}

.login__alert {
  margin-bottom: 16px;
}

.login__submit {
  width: 100%;
  height: 44px;
  margin-top: 6px;
  font-size: 14px;
  letter-spacing: 2px;
}

.login__demo {
  margin-top: 28px;
  padding: 14px 16px;
  border-radius: 10px;
  background: #f7f9fc;
  border: 1px dashed #e0e6f0;
}

.login__demo-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--sd-text-muted);
  margin-bottom: 10px;
}

.login__demo-pwd {
  color: var(--sd-text-secondary);
}

.login__demo-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.login__demo-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 4px;
  border-radius: 8px;
  border: 1px solid var(--sd-line);
  background: #fff;
  cursor: pointer;
  font-family: inherit;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.login__demo-item:hover {
  border-color: var(--sd-primary);
  box-shadow: 0 0 0 3px rgba(47, 107, 255, 0.08);
}

.login__demo-item strong {
  font-size: 12px;
  font-weight: 600;
  color: var(--sd-text);
}

.login__demo-item em {
  font-style: normal;
  font-size: 10px;
  color: var(--sd-text-muted);
}

@media (max-width: 960px) {
  .login__brand {
    display: none;
  }

  .login__panel {
    flex: 1 1 100%;
  }
}
</style>
