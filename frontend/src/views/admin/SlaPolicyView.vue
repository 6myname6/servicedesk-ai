<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { slaApi } from '@/api/sla'

/** 优先级下拉选项，取值与 sla_policy.priority、Urgency 枚举一致。 */
const PRIORITY_OPTIONS = [
  { value: 'P0', label: 'P0 紧急' },
  { value: 'P1', label: 'P1 标准' },
  { value: 'P2', label: 'P2 一般' },
  { value: 'P3', label: 'P3 低优' }
]

/** 策略列表加载状态。 */
const loading = ref(false)

/** 策略列表，后端已按优先级升序返回。 */
const rows = ref([])

/** 弹窗是否可见。 */
const dialogVisible = ref(false)

/** 弹窗模式：create 新增 / edit 编辑。 */
const dialogMode = ref('create')

/** 提交中标记，防止重复保存。 */
const saving = ref(false)

/** 表单组件引用，用于触发校验。 */
const formRef = ref(null)

/** 正在编辑的策略 ID，新增时为 null。 */
const editingId = ref(null)

/** 弹窗表单。 */
const form = reactive({
  priority: '',
  name: '',
  responseMinutes: 60,
  resolveMinutes: 240,
  enabled: true
})

/** 是否新增模式。 */
const isCreate = computed(() => dialogMode.value === 'create')

/** 已被占用的优先级，新增时在下拉中置灰，避免提交后才拿到 409。 */
const occupiedPriorities = computed(() => rows.value.map((row) => row.priority))

/**
 * 解决时限校验：必须为正整数，且不小于响应时限。
 *
 * @param {object} _rule 校验规则
 * @param {number} value 解决时限（分钟）
 * @param {Function} callback 校验回调
 */
function validateResolveMinutes(_rule, value, callback) {
  if (!Number.isInteger(value) || value <= 0) {
    callback(new Error('解决时限必须为正整数'))
    return
  }
  if (Number.isInteger(form.responseMinutes) && value < form.responseMinutes) {
    callback(new Error('解决时限不能小于响应时限'))
    return
  }
  callback()
}

/** 表单校验规则，口径与 SlaPolicyCreateRequest / SlaPolicyUpdateRequest 一致。 */
const rules = {
  priority: [{ required: true, message: '请选择优先级', trigger: 'change' }],
  name: [
    { required: true, message: '请输入策略名', trigger: 'blur' },
    { max: 64, message: '策略名不能超过 64 位', trigger: 'blur' }
  ],
  responseMinutes: [{ required: true, message: '请输入响应时限', trigger: 'blur' }],
  resolveMinutes: [
    { required: true, message: '请输入解决时限', trigger: 'blur' },
    { validator: validateResolveMinutes, trigger: 'blur' }
  ]
}

/**
 * 分钟数的可读时长，用于时限列的辅助说明。
 *
 * @param {number} minutes 分钟数
 * @returns {string} 例如「2 小时」「2 小时 30 分钟」；不足 1 小时返回空串
 */
function hourHint(minutes) {
  if (!Number.isInteger(minutes) || minutes < 60) {
    return ''
  }
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分钟`
}

/** 拉取策略列表。 */
async function loadPolicies() {
  loading.value = true
  try {
    rows.value = (await slaApi.list()) || []
  } finally {
    loading.value = false
  }
}

/** 重置表单为默认值。 */
function resetForm() {
  form.priority = ''
  form.name = ''
  form.responseMinutes = 60
  form.resolveMinutes = 240
  form.enabled = true
  editingId.value = null
}

/** 打开新增弹窗，默认选中第一个未被占用的优先级。 */
function openCreate() {
  resetForm()
  const free = PRIORITY_OPTIONS.find((item) => !occupiedPriorities.value.includes(item.value))
  form.priority = free ? free.value : ''
  dialogMode.value = 'create'
  dialogVisible.value = true
}

/**
 * 打开编辑弹窗，优先级只读。
 *
 * @param {object} row 策略行
 */
function openEdit(row) {
  editingId.value = row.id
  form.priority = row.priority
  form.name = row.name || ''
  form.responseMinutes = row.responseMinutes
  form.resolveMinutes = row.resolveMinutes
  form.enabled = row.enabled
  dialogMode.value = 'edit'
  dialogVisible.value = true
}

/** 响应时限变化后重新校验解决时限，避免旧的校验结论残留。 */
function revalidateResolveMinutes() {
  formRef.value?.validateField('resolveMinutes').catch(() => {})
}

/** 提交新增或修改。 */
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
    if (isCreate.value) {
      await slaApi.create({
        name: form.name.trim(),
        priority: form.priority,
        responseMinutes: form.responseMinutes,
        resolveMinutes: form.resolveMinutes,
        enabled: form.enabled
      })
      ElMessage.success('策略已创建')
    } else {
      await slaApi.update(editingId.value, {
        name: form.name.trim(),
        responseMinutes: form.responseMinutes,
        resolveMinutes: form.resolveMinutes,
        enabled: form.enabled
      })
      ElMessage.success('策略已更新，只影响之后创建的工单')
    }
    dialogVisible.value = false
    await loadPolicies()
  } finally {
    saving.value = false
  }
}

/**
 * 启用 / 停用策略：走修改接口整体覆盖，失败后刷新列表回滚开关状态。
 *
 * @param {object} row 策略行
 * @param {boolean} enabled 目标状态
 */
async function toggleEnabled(row, enabled) {
  try {
    await ElMessageBox.confirm(
      enabled
        ? `确定启用「${row.name}」吗？启用后新建工单会按 ${row.priority} 匹配本策略。`
        : `确定停用「${row.name}」吗？停用后新建工单不再匹配 ${row.priority}，历史工单不受影响。`,
      enabled ? '启用策略' : '停用策略',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await slaApi.update(row.id, {
      name: row.name,
      responseMinutes: row.responseMinutes,
      resolveMinutes: row.resolveMinutes,
      enabled
    })
    ElMessage.success(enabled ? '已启用' : '已停用')
  } finally {
    await loadPolicies()
  }
}

/**
 * 删除策略：无工单引用时物理删除，有引用时后端保留为停用。
 *
 * @param {object} row 策略行
 */
async function removePolicy(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.name}」（${row.priority}）吗？若该策略已被工单引用，将保留为停用状态。`,
      '删除策略',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await slaApi.remove(row.id)
    ElMessage.success('已删除；被工单引用的策略会保留为停用')
  } finally {
    await loadPolicies()
  }
}

onMounted(loadPolicies)
</script>

<template>
  <section class="sd-card sla-page">
    <div class="toolbar">
      <span class="toolbar__hint">
        按紧急度配置响应与解决时限；修改只影响之后创建的工单，已建工单的截止时间不变。
      </span>
      <el-button type="primary" plain @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新增策略</span>
      </el-button>
    </div>

    <el-table v-loading="loading" :data="rows" style="width: 100%">
      <el-table-column label="优先级" width="104">
        <template #default="{ row }">
          <span class="priority-tag" :class="`priority-tag--${row.priority.toLowerCase()}`">
            {{ row.priority }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="策略名" min-width="180">
        <template #default="{ row }">
          <span class="cell-strong">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="响应时限" width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.responseMinutes }} 分钟</span>
          <span v-if="hourHint(row.responseMinutes)" class="cell-sub">
            （{{ hourHint(row.responseMinutes) }}）
          </span>
        </template>
      </el-table-column>
      <el-table-column label="解决时限" width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.resolveMinutes }} 分钟</span>
          <span v-if="hourHint(row.resolveMinutes)" class="cell-sub">
            （{{ hourHint(row.resolveMinutes) }}）
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            inline-prompt
            active-text="启用"
            inactive-text="停用"
            @change="(value) => toggleEnabled(row, value)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removePolicy(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="table-empty">{{ loading ? '加载中…' : '还没有 SLA 策略' }}</div>
      </template>
    </el-table>

    <p class="sla-page__foot">
      共 {{ rows.length }} 条策略。某优先级没有启用中的策略时，建单会提示「SLA 策略未配置」。
    </p>

    <el-dialog
      v-model="dialogVisible"
      :title="isCreate ? '新增 SLA 策略' : '编辑 SLA 策略'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="优先级" prop="priority">
          <el-select
            v-if="isCreate"
            v-model="form.priority"
            class="full-width"
            placeholder="选择紧急度；已被占用的不可选"
          >
            <el-option
              v-for="item in PRIORITY_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
              :disabled="occupiedPriorities.includes(item.value)"
            />
          </el-select>
          <template v-else>
            <span class="priority-tag" :class="`priority-tag--${form.priority.toLowerCase()}`">
              {{ form.priority }}
            </span>
            <span class="readonly-hint">优先级是策略的匹配键，不可修改</span>
          </template>
        </el-form-item>
        <el-form-item label="策略名" prop="name">
          <el-input
            v-model="form.name"
            maxlength="64"
            show-word-limit
            placeholder="例如 P2 一般"
          />
        </el-form-item>
        <el-form-item label="响应时限" prop="responseMinutes">
          <el-input-number
            v-model="form.responseMinutes"
            class="minutes-input"
            :min="1"
            :step="1"
            :precision="0"
            controls-position="right"
            @change="revalidateResolveMinutes"
          />
          <span class="unit">分钟</span>
        </el-form-item>
        <el-form-item label="解决时限" prop="resolveMinutes">
          <el-input-number
            v-model="form.resolveMinutes"
            class="minutes-input"
            :min="1"
            :step="1"
            :precision="0"
            controls-position="right"
          />
          <span class="unit">分钟，不得小于响应时限</span>
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="form.enabled" />
          <span class="unit">停用后新单不再匹配本优先级</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.sla-page {
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

.toolbar__hint {
  font-size: 12px;
  color: var(--sd-text-muted);
}

.cell-strong {
  font-weight: 500;
  color: var(--sd-text);
}

.cell-sub {
  margin-left: 2px;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.priority-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 5px;
  background: #eef1f6;
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
}

.priority-tag--p0 {
  background: var(--sd-red-soft);
  color: var(--sd-red);
}

.priority-tag--p1 {
  background: var(--sd-orange-soft);
  color: var(--sd-orange);
}

.priority-tag--p2 {
  background: var(--sd-primary-soft);
  color: var(--sd-primary);
}

.priority-tag--p3 {
  background: #eef1f6;
  color: #64748b;
}

.sla-page__foot {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.table-empty {
  padding: 30px 0;
  color: var(--sd-text-muted);
}

.full-width {
  width: 100%;
}

.minutes-input {
  width: 150px;
}

.unit {
  margin-left: 10px;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.readonly-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--sd-text-muted);
}
</style>
