<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { changePassword } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

/** 与后端 ChangePasswordRequest 一致的密码强度规则：同时包含字母与数字。 */
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).+$/

const auth = useAuthStore()
const formRef = ref(null)
const saving = ref(false)

/** 改密表单，确认密码只在前端校验，不提交给后端。 */
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

/**
 * 校验两次输入的新密码是否一致。
 *
 * @param {object} rule 表单规则
 * @param {string} value 确认密码
 * @param {Function} callback 校验回调
 */
function validateConfirm(rule, value, callback) {
  if (value !== form.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
    return
  }
  callback()
}

/** 改密校验，口径与接口文档 1.7 一致。 */
const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 32, message: '密码长度需在 8-32 位', trigger: 'blur' },
    { pattern: PASSWORD_PATTERN, message: '新密码需同时包含字母与数字', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

/** 头像文字：显示名首字。 */
const avatarText = computed(() => (auth.user?.displayName || '用').slice(0, 1))

/** 归属展示：团队与客户互斥，都没有时给出明确空状态。 */
const org = computed(() => {
  if (auth.user?.teamName) {
    return { label: '所属团队', value: auth.user.teamName }
  }
  if (auth.user?.customerName) {
    return { label: '所属客户', value: auth.user.customerName }
  }
  return { label: '归属', value: '未分配团队或客户' }
})

/** 提交改密：成功后清空表单，不强制重新登录。 */
async function submit() {
  if (saving.value) {
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    await changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    ElMessage.success('密码已修改，下次登录请使用新密码')
    formRef.value?.resetFields()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  // 个人中心展示以服务端为准：刷新一次拿最新的团队 / 客户归属
  auth.loadMe().catch(() => {})
})
</script>

<template>
  <div class="profile">
    <section class="sd-card profile__intro">
      <span class="profile__avatar">{{ avatarText }}</span>
      <div class="profile__intro-text">
        <h1>{{ auth.user?.displayName || '未登录' }}</h1>
        <p>
          <span class="mono">{{ auth.user?.username }}</span>
          <span class="profile__dot">·</span>
          <span class="profile__role">{{ auth.roleLabel }}</span>
        </p>
      </div>
      <div class="profile__org">
        <span class="profile__org-label">{{ org.label }}</span>
        <strong>{{ org.value }}</strong>
      </div>
    </section>

    <div class="sd-grid-main">
      <section class="sd-card">
        <header class="sd-card__header">
          <h2 class="sd-card__title">基本信息</h2>
          <span class="sd-card__tip">资料由管理员在用户管理中维护</span>
        </header>

        <dl class="info">
          <div class="info__row">
            <dt>用户名</dt>
            <dd class="mono">{{ auth.user?.username || '—' }}</dd>
          </div>
          <div class="info__row">
            <dt>姓名</dt>
            <dd>{{ auth.user?.displayName || '—' }}</dd>
          </div>
          <div class="info__row">
            <dt>角色</dt>
            <dd>{{ auth.roleLabel || '—' }}</dd>
          </div>
          <div class="info__row">
            <dt>{{ org.label }}</dt>
            <dd>{{ org.value }}</dd>
          </div>
          <div class="info__row">
            <dt>手机号</dt>
            <dd class="mono">{{ auth.user?.phone || '未填写' }}</dd>
          </div>
          <div class="info__row">
            <dt>邮箱</dt>
            <dd>{{ auth.user?.email || '未填写' }}</dd>
          </div>
        </dl>
      </section>

      <aside>
        <section class="sd-card">
          <header class="sd-card__header">
            <h2 class="sd-card__title">密码管理</h2>
            <span class="sd-card__tip">8-32 位，含字母与数字</span>
          </header>

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            @submit.prevent="submit"
          >
            <el-form-item label="原密码" prop="oldPassword">
              <el-input
                v-model="form.oldPassword"
                type="password"
                show-password
                autocomplete="current-password"
                placeholder="请输入当前密码"
              />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input
                v-model="form.newPassword"
                type="password"
                show-password
                autocomplete="new-password"
                placeholder="8-32 位，需同时包含字母与数字"
              />
            </el-form-item>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input
                v-model="form.confirmPassword"
                type="password"
                show-password
                autocomplete="new-password"
                placeholder="再次输入新密码"
              />
            </el-form-item>

            <el-button type="primary" class="profile__submit" :loading="saving" @click="submit">
              保存新密码
            </el-button>
            <p class="profile__hint">修改后当前登录不会失效，其他设备的登录按有效期自然过期。</p>
          </el-form>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.profile {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile__intro {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
}

.profile__avatar {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--sd-primary, #2f6bff);
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  flex-shrink: 0;
}

.profile__intro-text h1 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 600;
}

.profile__intro-text p {
  margin: 0;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.profile__dot {
  margin: 0 6px;
}

.profile__role {
  color: var(--sd-primary, #2f6bff);
}

.profile__org {
  margin-left: auto;
  text-align: right;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.profile__org-label {
  font-size: 11px;
  color: var(--sd-text-muted);
}

.profile__org strong {
  font-size: 13px;
}

.info {
  margin: 0;
}

.info__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--sd-line, #e6e8ee);
}

.info__row:last-child {
  border-bottom: none;
}

.info dt {
  width: 96px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.info dd {
  margin: 0;
  font-size: 13px;
}

.profile__submit {
  width: 100%;
  margin-top: 4px;
}

.profile__hint {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--sd-text-muted);
}
</style>
