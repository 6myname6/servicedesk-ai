<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Avatar, Lock, Message, User } from '@element-plus/icons-vue'

/** 验证码倒计时总秒数，与产品文档 P-12 的 60 秒一致。 */
const COUNTDOWN_SECONDS = 60

/** 密码规则：8-32 位且同时包含字母与数字。 */
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d\S]{8,32}$/

const router = useRouter()

const formRef = ref(null)
const submitting = ref(false)
const sending = ref(false)

/** 验证码剩余倒计时，0 表示可再次发送。 */
const countdown = ref(0)

/** 倒计时定时器句柄。 */
let countdownTimer = null

/** 注册表单。 */
const form = reactive({
  email: '',
  code: '',
  username: '',
  password: '',
  displayName: ''
})

/**
 * 密码复杂度校验。
 *
 * @param {object} rule 校验规则
 * @param {string} value 输入值
 * @param {Function} callback 回调
 */
function validatePassword(rule, value, callback) {
  if (!value) {
    callback(new Error('请输入密码'))
    return
  }
  if (!PASSWORD_PATTERN.test(value)) {
    callback(new Error('密码 8-32 位，且需同时包含字母与数字'))
    return
  }
  callback()
}

/** 表单校验规则。 */
const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: '用户名为 4-20 位字母、数字或下划线', trigger: 'blur' }
  ],
  password: [{ validator: validatePassword, trigger: 'blur' }],
  displayName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 20, message: '姓名不能超过 20 位', trigger: 'blur' }
  ]
}

/** 清空倒计时并释放定时器。 */
function stopCountdown() {
  if (countdownTimer) {
    window.clearInterval(countdownTimer)
    countdownTimer = null
  }
}

/** 发送邮箱验证码：校验邮箱后进入 60 秒倒计时。 */
async function sendCode() {
  if (countdown.value > 0 || sending.value) {
    return
  }
  if (!formRef.value) {
    return
  }
  const emailValid = await formRef.value
    .validateField('email')
    .then(() => true)
    .catch(() => false)
  if (!emailValid) {
    return
  }

  sending.value = true
  try {
    // 原型：真实实现调用 POST /auth/email-code
    await new Promise((resolve) => window.setTimeout(resolve, 400))
    ElMessage.success('验证码已发送，5 分钟内有效（原型环境任意 6 位数字均可）')
    countdown.value = COUNTDOWN_SECONDS
    stopCountdown()
    countdownTimer = window.setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) {
        stopCountdown()
      }
    }, 1000)
  } finally {
    sending.value = false
  }
}

/**
 * 提交注册。
 *
 * <p>注册接口尚未实现，原型只做校验与结果演示，不写入本地登录态，
 * 避免假 token 影响真实接口调用。</p>
 */
async function submit() {
  if (submitting.value) {
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    await new Promise((resolve) => window.setTimeout(resolve, 500))
    await ElMessageBox.alert(
      `注册信息校验通过。原型阶段未接入注册接口，未创建真实账号；` +
        `接口就绪后将直接返回 token 并进入工作台。用户名为 ${form.username}。`,
      '注册原型演示',
      { confirmButtonText: '去登录', type: 'success' }
    )
    router.push({ path: '/login', query: { username: form.username } })
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(stopCountdown)
</script>

<template>
  <div class="register">
    <section class="register__brand">
      <div class="register__brand-top">
        <span class="register__logo">
          <el-icon><Service /></el-icon>
        </span>
        <span class="register__logo-text">
          <strong>智维工单</strong>
          <em>ServiceDesk AI</em>
        </span>
      </div>

      <div class="register__brand-main">
        <h1>先有账号<br />再让问题闭环</h1>
        <p>注册后即可提交报修、查看处理进度，并在工单关闭后对服务做出评价。</p>

        <ul class="register__features">
          <li>
            <span class="register__feature-icon"><el-icon><EditPen /></el-icon></span>
            <span>
              <strong>快速报修</strong>
              <em>标题、描述、图片，一分钟内提交</em>
            </span>
          </li>
          <li>
            <span class="register__feature-icon"><el-icon><Bell /></el-icon></span>
            <span>
              <strong>进度透明</strong>
              <em>处理时间线与 SLA 倒计时随时可查</em>
            </span>
          </li>
          <li>
            <span class="register__feature-icon"><el-icon><Star /></el-icon></span>
            <span>
              <strong>服务可评价</strong>
              <em>关闭后 1-5 分评价，推动服务质量</em>
            </span>
          </li>
        </ul>
      </div>

      <p class="register__brand-footer">企业售后 / 设备报修工单系统 · MVP</p>
    </section>

    <section class="register__panel">
      <div class="register__form-wrap">
        <h2>创建账号</h2>
        <p class="register__subtitle">邮箱 + 验证码注册，注册后自动登录</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="submit"
        >
          <el-form-item label="邮箱" prop="email">
            <el-input
              v-model="form.email"
              size="large"
              placeholder="name@company.com"
              autocomplete="email"
              :prefix-icon="Message"
            />
          </el-form-item>

          <el-form-item label="验证码" prop="code">
            <div class="register__code">
              <el-input
                v-model="form.code"
                size="large"
                maxlength="6"
                placeholder="6 位数字"
                autocomplete="one-time-code"
              />
              <el-button
                size="large"
                class="register__code-btn"
                :loading="sending"
                :disabled="countdown > 0"
                @click="sendCode"
              >
                {{ countdown > 0 ? `${countdown} 秒后重发` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>

          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              size="large"
              placeholder="4-20 位字母、数字或下划线"
              autocomplete="username"
              :prefix-icon="User"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              placeholder="8-32 位，需同时包含字母与数字"
              autocomplete="new-password"
              show-password
              :prefix-icon="Lock"
            />
          </el-form-item>

          <el-form-item label="姓名" prop="displayName">
            <el-input
              v-model="form.displayName"
              size="large"
              maxlength="20"
              placeholder="用于工单与通知中的显示名"
              autocomplete="name"
              :prefix-icon="Avatar"
            />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="register__submit"
            :loading="submitting"
            @click="submit"
          >
            {{ submitting ? '提交中…' : '注册' }}
          </el-button>
        </el-form>

        <p class="register__foot">
          已有账号？
          <router-link to="/login">返回登录</router-link>
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.register {
  min-height: 100vh;
  display: flex;
  background: #fff;
}

/* 左侧品牌区 */
.register__brand {
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

.register__brand::after {
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

.register__brand-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
}

.register__logo {
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

.register__logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.register__logo-text strong {
  color: #fff;
  font-size: 17px;
  letter-spacing: 1.5px;
}

.register__logo-text em {
  font-style: normal;
  font-size: 10px;
  color: #8ba3b9;
  letter-spacing: 0.5px;
}

.register__brand-main {
  position: relative;
  z-index: 1;
  max-width: 520px;
  margin: 32px 0;
}

.register__brand-main h1 {
  margin: 0;
  font-size: 40px;
  line-height: 1.3;
  letter-spacing: 2px;
  color: #fff;
  font-weight: 600;
}

.register__brand-main > p {
  margin: 18px 0 32px;
  font-size: 13px;
  line-height: 1.9;
  color: #9fb4c8;
  max-width: 440px;
}

.register__features {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.register__features li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.register__feature-icon {
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

.register__features strong {
  display: block;
  color: #eaf3fb;
  font-size: 13px;
  font-weight: 500;
}

.register__features em {
  font-style: normal;
  font-size: 12px;
  color: #8ba3b9;
}

.register__brand-footer {
  position: relative;
  z-index: 1;
  margin: 0;
  font-size: 11px;
  color: #6f889f;
}

/* 右侧表单 */
.register__panel {
  flex: 1 1 44%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 36px 40px;
  overflow-y: auto;
}

.register__form-wrap {
  width: 100%;
  max-width: 380px;
}

.register__form-wrap h2 {
  margin: 0;
  font-size: 24px;
  letter-spacing: 1px;
}

.register__subtitle {
  margin: 8px 0 22px;
  color: var(--sd-text-muted);
  font-size: 13px;
}

.register__code {
  display: flex;
  gap: 10px;
  width: 100%;
}

.register__code-btn {
  flex-shrink: 0;
  width: 126px;
  font-size: 12px;
}

.register__submit {
  width: 100%;
  height: 44px;
  margin-top: 4px;
  font-size: 14px;
  letter-spacing: 2px;
}

.register__foot {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--sd-text-muted);
}

@media (max-width: 960px) {
  .register__brand {
    display: none;
  }

  .register__panel {
    flex: 1 1 100%;
  }
}
</style>
