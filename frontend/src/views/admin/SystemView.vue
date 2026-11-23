<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import { customerApi, teamApi, userApi } from '@/api/admin'
import { ROLE_LABELS, ROLE_OPTIONS } from '@/utils/dict'

/** 分页默认每页条数。 */
const DEFAULT_SIZE = 10

/** 用户状态筛选项：1 启用 / 0 停用，与 sys_user.status 取值一致。 */
const USER_STATUS_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' }
]

const route = useRoute()
const router = useRouter()

/** 当前 Tab 由路由决定，切换 Tab 时同步地址栏，保证面包屑与刷新一致。 */
const activeTab = computed({
  get: () => (route.name === 'admin-teams' ? 'team' : 'user'),
  set: (value) => {
    router.replace(value === 'team' ? '/admin/teams' : '/admin/users')
  }
})

/* ---------------- 用户管理 ---------------- */

const userLoading = ref(false)
const userRows = ref([])
const userPage = reactive({ page: 1, size: DEFAULT_SIZE, total: 0 })
const userKeyword = ref('')
const userRoleFilter = ref('')
const userStatusFilter = ref(null)

const userDialogVisible = ref(false)
const userDialogMode = ref('create')
const userSaving = ref(false)
const userFormRef = ref(null)
const editingUserId = ref(null)

const userForm = reactive({
  username: '',
  password: '',
  displayName: '',
  role: 'ENGINEER',
  teamId: null,
  customerId: null,
  phone: '',
  email: ''
})

/** 新增用户校验，口径与 UserCreateRequest 一致。 */
const userCreateRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度需在 3-32 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 32, message: '密码长度需在 8-32 位', trigger: 'blur' }
  ],
  displayName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 32, message: '姓名不能超过 32 位', trigger: 'blur' }
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  phone: [{ max: 20, message: '手机号不能超过 20 位', trigger: 'blur' }],
  email: [
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 64, message: '邮箱不能超过 64 位', trigger: 'blur' }
  ]
}

/** 编辑用户校验：登录名与密码不可在此修改。 */
const userEditRules = {
  displayName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { max: 32, message: '姓名不能超过 32 位', trigger: 'blur' }
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  phone: [{ max: 20, message: '手机号不能超过 20 位', trigger: 'blur' }],
  email: [
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 64, message: '邮箱不能超过 64 位', trigger: 'blur' }
  ]
}

/** 用户分页区间文案。 */
const userRangeText = computed(() => {
  if (userPage.total === 0) {
    return '暂无数据'
  }
  const start = (userPage.page - 1) * userPage.size + 1
  const end = Math.min(userPage.page * userPage.size, userPage.total)
  return `显示 ${start}-${end} 条，共 ${userPage.total} 条`
})

/* ---------------- 团队管理 ---------------- */

const teamLoading = ref(false)
const teamRows = ref([])

const teamDialogVisible = ref(false)
const teamDialogMode = ref('create')
const teamSaving = ref(false)
const teamFormRef = ref(null)
const editingTeamId = ref(null)

const teamForm = reactive({
  name: '',
  supervisorId: null,
  description: ''
})

/** 团队表单校验，口径与 TeamCreateRequest 一致。 */
const teamRules = {
  name: [
    { required: true, message: '请输入团队名', trigger: 'blur' },
    { max: 64, message: '团队名不能超过 64 位', trigger: 'blur' }
  ],
  description: [{ max: 255, message: '描述不能超过 255 位', trigger: 'blur' }]
}

/** 用户下拉选项，团队主管选择用。 */
const userOptions = ref([])

/** 客户下拉选项，用户归属选择用；与团队互斥。 */
const customerOptions = ref([])

/**
 * 选择团队时清空客户：一个账号只能有一种归属，前端先做一次，避免提交后被 400 拒绝。
 */
function onTeamChange() {
  if (userForm.teamId) {
    userForm.customerId = null
  }
}

/**
 * 选择客户时清空团队，与 {@link onTeamChange} 对称。
 */
function onCustomerChange() {
  if (userForm.customerId) {
    userForm.teamId = null
  }
}

/** 拉取用户列表。 */
async function loadUsers() {
  userLoading.value = true
  try {
    const status = userStatusFilter.value
    const data = await userApi.page({
      keyword: userKeyword.value.trim() || undefined,
      role: userRoleFilter.value || undefined,
      // 清空后 Element Plus 可能给出 null / undefined / ''，只传真正的 0 或 1
      status: typeof status === 'number' ? status : undefined,
      page: userPage.page,
      size: userPage.size
    })
    userRows.value = data?.records || []
    userPage.total = data?.total || 0
  } finally {
    userLoading.value = false
  }
}

/** 拉取全部团队。 */
async function loadTeams() {
  teamLoading.value = true
  try {
    teamRows.value = (await teamApi.list()) || []
  } finally {
    teamLoading.value = false
  }
}

/** 拉取用户下拉选项，取前 100 条。 */
async function loadUserOptions() {
  const data = await userApi.page({ page: 1, size: 100 })
  userOptions.value = data?.records || []
}

/** 拉取客户下拉选项，取前 100 条。 */
async function loadCustomerOptions() {
  const data = await customerApi.page({ page: 1, size: 100 })
  customerOptions.value = data?.records || []
}

/** 用户查询：回到第一页。 */
function searchUsers() {
  userPage.page = 1
  loadUsers()
}

/** 重置用户筛选。 */
function resetUserSearch() {
  userKeyword.value = ''
  userRoleFilter.value = ''
  userStatusFilter.value = null
  searchUsers()
}

/**
 * 用户分页变化。
 *
 * @param {number} page 页码
 * @param {number} size 每页条数
 */
function changeUserPage(page, size) {
  userPage.page = size !== userPage.size ? 1 : page
  userPage.size = size
  loadUsers()
}

/** 重置用户表单。 */
function resetUserForm() {
  userForm.username = ''
  userForm.password = ''
  userForm.displayName = ''
  userForm.role = 'ENGINEER'
  userForm.teamId = null
  userForm.customerId = null
  userForm.phone = ''
  userForm.email = ''
  editingUserId.value = null
}

/** 打开新增用户弹窗。 */
function openUserCreate() {
  resetUserForm()
  userDialogMode.value = 'create'
  userDialogVisible.value = true
}

/**
 * 打开编辑用户弹窗。
 *
 * @param {object} row 用户行
 */
function openUserEdit(row) {
  userDialogMode.value = 'edit'
  editingUserId.value = row.id
  userForm.username = row.username
  userForm.password = ''
  userForm.displayName = row.displayName || ''
  userForm.role = row.role || 'ENGINEER'
  userForm.teamId = row.teamId ?? null
  userForm.customerId = row.customerId ?? null
  userForm.phone = row.phone || ''
  userForm.email = row.email || ''
  userDialogVisible.value = true
}

/** 提交用户表单。 */
async function submitUser() {
  if (userSaving.value) {
    return
  }
  const valid = await userFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  userSaving.value = true
  try {
    if (userDialogMode.value === 'create') {
      await userApi.create({
        username: userForm.username.trim(),
        password: userForm.password,
        displayName: userForm.displayName.trim(),
        role: userForm.role,
        teamId: userForm.teamId || null,
        customerId: userForm.customerId || null,
        phone: userForm.phone || null,
        email: userForm.email || null
      })
      ElMessage.success('用户已创建')
    } else {
      await userApi.update(editingUserId.value, {
        displayName: userForm.displayName.trim(),
        role: userForm.role,
        teamId: userForm.teamId || null,
        customerId: userForm.customerId || null,
        phone: userForm.phone || null,
        email: userForm.email || null
      })
      ElMessage.success('用户已更新')
    }
    userDialogVisible.value = false
    await Promise.all([loadUsers(), loadUserOptions()])
  } finally {
    userSaving.value = false
  }
}

/**
 * 启用 / 停用用户，失败时回滚界面状态。
 *
 * @param {object} row 用户行
 * @param {boolean} enabled 目标状态
 */
async function toggleUserStatus(row, enabled) {
  try {
    await ElMessageBox.confirm(
      `确定${enabled ? '启用' : '停用'}「${row.displayName}」吗？${enabled ? '' : '停用后该账号无法登录。'}`,
      enabled ? '启用用户' : '停用用户',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await userApi.updateStatus(row.id, enabled ? 1 : 0)
    ElMessage.success(enabled ? '已启用' : '已停用')
  } finally {
    await loadUsers()
  }
}

/** 重置团队表单。 */
function resetTeamForm() {
  teamForm.name = ''
  teamForm.supervisorId = null
  teamForm.description = ''
  editingTeamId.value = null
}

/** 打开新增团队弹窗。 */
function openTeamCreate() {
  resetTeamForm()
  teamDialogMode.value = 'create'
  teamDialogVisible.value = true
}

/**
 * 打开编辑团队弹窗。
 *
 * @param {object} row 团队行
 */
function openTeamEdit(row) {
  teamDialogMode.value = 'edit'
  editingTeamId.value = row.id
  teamForm.name = row.name || ''
  teamForm.supervisorId = row.supervisorId ?? null
  teamForm.description = row.description || ''
  teamDialogVisible.value = true
}

/** 提交团队表单。 */
async function submitTeam() {
  if (teamSaving.value) {
    return
  }
  const valid = await teamFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  teamSaving.value = true
  try {
    const payload = {
      name: teamForm.name.trim(),
      supervisorId: teamForm.supervisorId || null,
      description: teamForm.description || null
    }
    if (teamDialogMode.value === 'create') {
      await teamApi.create(payload)
      ElMessage.success('团队已创建')
    } else {
      await teamApi.update(editingTeamId.value, payload)
      ElMessage.success('团队已更新')
    }
    teamDialogVisible.value = false
    await Promise.all([loadTeams(), loadUserOptions()])
  } finally {
    teamSaving.value = false
  }
}

/**
 * 角色标签样式后缀。
 *
 * @param {string} role 角色编码
 * @returns {string} 小写角色名
 */
function roleVariant(role) {
  return String(role || '').toLowerCase()
}

onMounted(() => {
  loadUsers()
  loadTeams()
  loadUserOptions()
  loadCustomerOptions()
})
</script>

<template>
  <section class="sd-card system">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="用户管理" name="user">
        <div class="toolbar">
          <div class="toolbar__left">
            <el-input
              v-model="userKeyword"
              class="toolbar__keyword"
              placeholder="搜索用户名 / 姓名"
              clearable
              @keyup.enter="searchUsers"
              @clear="searchUsers"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-select
              v-model="userRoleFilter"
              class="toolbar__select"
              placeholder="全部角色"
              clearable
              @change="searchUsers"
            >
              <el-option
                v-for="item in ROLE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select
              v-model="userStatusFilter"
              class="toolbar__select toolbar__select--sm"
              placeholder="全部状态"
              clearable
              @change="searchUsers"
            >
              <el-option
                v-for="item in USER_STATUS_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-button type="primary" @click="searchUsers">查询</el-button>
            <el-button @click="resetUserSearch">重置</el-button>
          </div>
          <el-button type="primary" plain @click="openUserCreate">
            <el-icon><Plus /></el-icon>
            <span>新增用户</span>
          </el-button>
        </div>

        <el-table v-loading="userLoading" :data="userRows" style="width: 100%">
          <el-table-column prop="username" label="用户名" width="140">
            <template #default="{ row }">
              <span class="mono cell-strong">{{ row.username }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="displayName" label="姓名" width="120" />
          <el-table-column label="角色" width="104">
            <template #default="{ row }">
              <span class="role-tag" :class="`role-tag--${roleVariant(row.role)}`">
                {{ ROLE_LABELS[row.role] || row.role }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="归属" min-width="150">
            <template #default="{ row }">
              {{ row.teamName || row.customerName || '—' }}
            </template>
          </el-table-column>
          <el-table-column prop="phone" label="手机号" width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.phone || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="email" label="邮箱" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">{{ row.email || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-switch
                :model-value="row.status === 1"
                inline-prompt
                active-text="启用"
                inactive-text="停用"
                @change="(value) => toggleUserStatus(row, value)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openUserEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="table-empty">{{ userLoading ? '加载中…' : '还没有用户' }}</div>
          </template>
        </el-table>

        <div class="table-footer">
          <span class="table-footer__range">{{ userRangeText }}</span>
          <el-pagination
            :current-page="userPage.page"
            :page-size="userPage.size"
            :page-sizes="[10, 20, 50]"
            :total="userPage.total"
            layout="sizes, prev, pager, next"
            background
            size="small"
            @current-change="(page) => changeUserPage(page, userPage.size)"
            @size-change="(size) => changeUserPage(userPage.page, size)"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="团队管理" name="team">
        <div class="toolbar">
          <span class="toolbar__hint">团队是派单与数据范围的边界，主管只能看到本团队工单。</span>
          <el-button type="primary" plain @click="openTeamCreate">
            <el-icon><Plus /></el-icon>
            <span>新增团队</span>
          </el-button>
        </div>

        <el-table v-loading="teamLoading" :data="teamRows" style="width: 100%">
          <el-table-column prop="name" label="团队名" min-width="180">
            <template #default="{ row }">
              <span class="cell-strong">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="主管" width="140">
            <template #default="{ row }">{{ row.supervisorName || '未指定' }}</template>
          </el-table-column>
          <el-table-column label="成员数" width="100">
            <template #default="{ row }">
              <span class="mono">{{ row.memberCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTeamEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="table-empty">{{ teamLoading ? '加载中…' : '还没有团队' }}</div>
          </template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 用户表单 -->
    <el-dialog
      v-model="userDialogVisible"
      :title="userDialogMode === 'create' ? '新增用户' : '编辑用户'"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="userFormRef"
        :model="userForm"
        :rules="userDialogMode === 'create' ? userCreateRules : userEditRules"
        label-width="82px"
      >
        <el-form-item v-if="userDialogMode === 'create'" label="用户名" prop="username">
          <el-input v-model="userForm.username" maxlength="32" placeholder="3-32 位，登录凭证" />
        </el-form-item>
        <el-form-item v-if="userDialogMode === 'create'" label="初始密码" prop="password">
          <el-input
            v-model="userForm.password"
            type="password"
            maxlength="32"
            show-password
            placeholder="8-32 位，落库前 BCrypt 加密"
          />
        </el-form-item>
        <el-form-item label="姓名" prop="displayName">
          <el-input v-model="userForm.displayName" maxlength="32" placeholder="显示名" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="userForm.role" class="full-width">
            <el-option
              v-for="item in ROLE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属团队">
          <el-select
            v-model="userForm.teamId"
            class="full-width"
            clearable
            placeholder="内部员工选择团队；客户联系人改用下一行的所属客户"
            @change="onTeamChange"
          >
            <el-option
              v-for="item in teamRows"
              :key="item.id"
              :label="`${item.name}（${item.memberCount} 人）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属客户">
          <el-select
            v-model="userForm.customerId"
            class="full-width"
            clearable
            filterable
            placeholder="客户联系人选择所属客户；与团队只能二选一"
            @change="onCustomerChange"
          >
            <el-option
              v-for="item in customerOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="userForm.phone" maxlength="20" placeholder="选填" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="userForm.email" maxlength="64" placeholder="选填，填写时全局唯一" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="userSaving" @click="submitUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 团队表单 -->
    <el-dialog
      v-model="teamDialogVisible"
      :title="teamDialogMode === 'create' ? '新增团队' : '编辑团队'"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form ref="teamFormRef" :model="teamForm" :rules="teamRules" label-width="82px">
        <el-form-item label="团队名" prop="name">
          <el-input v-model="teamForm.name" maxlength="64" placeholder="全局唯一" />
        </el-form-item>
        <el-form-item label="主管">
          <el-select v-model="teamForm.supervisorId" class="full-width" clearable placeholder="选填">
            <el-option
              v-for="item in userOptions"
              :key="item.id"
              :label="`${item.displayName}（${ROLE_LABELS[item.role] || item.role}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="teamForm.description"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="选填，例如负责片区"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="teamDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="teamSaving" @click="submitTeam">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.system {
  padding: 8px 20px 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0 14px;
  flex-wrap: wrap;
}

.toolbar__left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.toolbar__keyword {
  width: 220px;
}

.toolbar__select {
  width: 132px;
}

.toolbar__select--sm {
  width: 116px;
}

.toolbar__hint {
  font-size: 12px;
  color: var(--sd-text-muted);
}

.cell-strong {
  font-weight: 500;
  color: var(--sd-text);
}

.role-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 5px;
  background: #eef1f6;
  color: #64748b;
  font-size: 11px;
}

.role-tag--admin {
  background: var(--sd-purple-soft);
  color: var(--sd-purple);
}

.role-tag--supervisor {
  background: #e6f7f4;
  color: #0f766e;
}

.role-tag--engineer {
  background: #e8f0ff;
  color: #2563eb;
}

.table-empty {
  padding: 30px 0;
  color: var(--sd-text-muted);
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 14px;
}

.table-footer__range {
  font-size: 12px;
  color: var(--sd-text-muted);
}

.full-width {
  width: 100%;
}
</style>
