<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { createTicket } from '@/api/tickets'
import { customerApi, deviceApi } from '@/api/admin'
import { uploadFile } from '@/api/files'
import { useAuthStore } from '@/stores/auth'
import {
  CATEGORY_OPTIONS,
  SLA_POLICY_PREVIEW,
  URGENCY_HINTS,
  URGENCY_OPTIONS
} from '@/utils/dict'
import { formatDateTime, formatFileSize } from '@/utils/format'

/** 附件数量与大小上限，与后端 FileBizType.TICKET 一致。 */
const MAX_ATTACHMENTS = 5
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

const router = useRouter()
const auth = useAuthStore()

const formRef = ref(null)
const submitting = ref(false)
const successVisible = ref(false)
const createdTicket = ref(null)

const customerLoading = ref(false)
const customerOptions = ref([])
const deviceLoading = ref(false)
const deviceOptions = ref([])

/** 待关联附件：uid 用于和 el-upload 列表对齐，fileUrl 提交时传给后端。 */
const attachments = ref([])
/** el-upload 的受控文件列表，重置表单时需要一并清空。 */
const fileList = ref([])

const form = reactive({
  title: '',
  description: '',
  customerId: null,
  deviceId: null,
  category: 'OTHER',
  urgency: 'P2'
})

/** 表单校验规则，长度口径与后端 TicketCreateRequest 保持一致。 */
const rules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 128, message: '标题不能超过 128 位', trigger: 'blur' }
  ],
  description: [
    { required: true, message: '请输入问题描述', trigger: 'blur' },
    { max: 5000, message: '描述不能超过 5000 位', trigger: 'blur' }
  ],
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }]
}

/** 是否具备客户/设备档案查询权限（主管、管理员）。 */
const canPickCustomer = computed(() => auth.canManageAssets)

/** 当前紧急度对应的 SLA 预览。 */
const slaPreview = computed(() => SLA_POLICY_PREVIEW[form.urgency] || SLA_POLICY_PREVIEW.P2)

/** 当前紧急度的文字说明。 */
const urgencyHint = computed(() => URGENCY_HINTS[form.urgency] || '')

/**
 * 分钟转「N 分钟 / N 小时」，用于 SLA 预览。
 *
 * @param {number} minutes 分钟数
 * @returns {string} 展示文案
 */
function humanizeMinutes(minutes) {
  if (minutes < 60) {
    return `${minutes} 分钟`
  }
  return `${Math.round(minutes / 60)} 小时`
}

/**
 * 查询客户档案，供下拉选择。
 *
 * @param {string} keyword 关键字，可为空
 */
async function loadCustomers(keyword = '') {
  if (!canPickCustomer.value) {
    return
  }
  customerLoading.value = true
  try {
    const data = await customerApi.page({ keyword: keyword || undefined, page: 1, size: 100 })
    customerOptions.value = data?.records || []
  } finally {
    customerLoading.value = false
  }
}

/**
 * 按客户查询可选设备。
 *
 * @param {number|null} customerId 客户 ID
 */
async function loadDevices(customerId) {
  if (!canPickCustomer.value || !customerId) {
    deviceOptions.value = []
    return
  }
  deviceLoading.value = true
  try {
    const data = await deviceApi.page({ customerId, page: 1, size: 100 })
    deviceOptions.value = data?.records || []
  } finally {
    deviceLoading.value = false
  }
}

watch(
  () => form.customerId,
  (customerId, previous) => {
    // 换客户后原设备必然不属于新客户，直接清空避免提交被后端拒绝
    if (previous !== undefined && customerId !== previous) {
      form.deviceId = null
    }
    loadDevices(customerId)
  }
)

/**
 * 上传前校验扩展名与大小，与后端白名单一致。
 *
 * @param {File} file 文件
 * @returns {boolean} 是否允许上传
 */
function beforeUpload(file) {
  const allowed = ['jpg', 'jpeg', 'png']
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!allowed.includes(extension)) {
    ElMessage.warning('仅支持 jpg / jpeg / png 图片')
    return false
  }
  if (file.size > MAX_ATTACHMENT_BYTES) {
    ElMessage.warning(`单张图片不能超过 ${formatFileSize(MAX_ATTACHMENT_BYTES)}`)
    return false
  }
  return true
}

/**
 * 自定义上传：直接调用附件接口，成功后把 URL 记入待关联列表。
 *
 * @param {object} options el-upload 的请求参数
 */
async function handleUpload(options) {
  try {
    const data = await uploadFile(options.file, 'TICKET')
    attachments.value.push({ uid: options.file.uid, id: data.id, fileUrl: data.fileUrl })
    options.onSuccess(data)
  } catch (error) {
    options.onError(error)
  }
}

/**
 * 移除已上传图片时同步移除待关联 URL。
 *
 * @param {object} file el-upload 的文件对象
 */
function handleRemove(file) {
  attachments.value = attachments.value.filter((item) => item.uid !== file.uid)
}

/** 超出数量上限时的提示。 */
function handleExceed() {
  ElMessage.warning(`最多上传 ${MAX_ATTACHMENTS} 张图片`)
}

/** 提交建单。 */
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
    const payload = {
      title: form.title.trim(),
      description: form.description.trim(),
      customerId: form.customerId,
      category: form.category,
      urgency: form.urgency,
      imageUrls: attachments.value.map((item) => item.fileUrl)
    }
    if (form.deviceId) {
      payload.deviceId = form.deviceId
    }
    const detail = await createTicket(payload)
    createdTicket.value = detail
    successVisible.value = true
  } finally {
    submitting.value = false
  }
}

/** 取消并返回工单列表。 */
function cancel() {
  router.push('/tickets')
}

/** 建单成功后进入详情页。 */
function goDetail() {
  const id = createdTicket.value?.id
  successVisible.value = false
  if (id) {
    router.push(`/tickets/${id}`)
  }
}

/** 关闭成功弹窗后重置表单，继续建下一张。 */
function createAnother() {
  successVisible.value = false
  createdTicket.value = null
  formRef.value?.resetFields()
  form.title = ''
  form.description = ''
  form.customerId = null
  form.deviceId = null
  form.category = 'OTHER'
  form.urgency = 'P2'
  attachments.value = []
  fileList.value = []
  deviceOptions.value = []
}

onMounted(() => {
  loadCustomers()
})
</script>

<template>
  <div class="ticket-create">
    <div class="sd-grid-main">
      <section class="sd-card">
        <div class="sd-card__header">
          <h2 class="sd-card__title">基本信息</h2>
          <span class="sd-card__tip">带 <em class="required">*</em> 为必填</span>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <div class="form-row">
            <el-form-item label="客户" prop="customerId" class="form-col">
              <el-select
                v-if="canPickCustomer"
                v-model="form.customerId"
                filterable
                remote
                clearable
                :remote-method="loadCustomers"
                :loading="customerLoading"
                placeholder="搜索并选择客户"
              >
                <el-option
                  v-for="item in customerOptions"
                  :key="item.id"
                  :label="item.level === 'KEY' ? `${item.name}（重点）` : item.name"
                  :value="item.id"
                />
              </el-select>
              <div v-else class="manual-field">
                <el-input-number
                  v-model="form.customerId"
                  :min="1"
                  :controls="false"
                  placeholder="请输入客户 ID"
                  class="manual-field__input"
                />
                <span class="manual-field__tip">
                  客户档案查询接口仅对主管/管理员开放，报修人需填写客户 ID（演示数据为 1-30）
                </span>
              </div>
            </el-form-item>

            <el-form-item label="设备（可选）" class="form-col">
              <el-select
                v-if="canPickCustomer"
                v-model="form.deviceId"
                clearable
                filterable
                :loading="deviceLoading"
                :disabled="!form.customerId"
                :placeholder="form.customerId ? '选择该客户名下的设备' : '请先选择客户'"
              >
                <el-option
                  v-for="item in deviceOptions"
                  :key="item.id"
                  :label="`${item.model || '未登记型号'} / ${item.serialNo}`"
                  :value="item.id"
                />
              </el-select>
              <el-input-number
                v-else
                v-model="form.deviceId"
                :min="1"
                :controls="false"
                placeholder="请输入设备 ID（可留空）"
                class="manual-field__input"
              />
            </el-form-item>
          </div>

          <el-form-item label="标题" prop="title">
            <el-input
              v-model="form.title"
              maxlength="128"
              show-word-limit
              placeholder="一句话说明问题，例如：空压机运行异响"
            />
          </el-form-item>

          <el-form-item label="描述" prop="description">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="4"
              maxlength="5000"
              show-word-limit
              placeholder="出现时间、现象、已尝试的处理、对生产的影响"
            />
          </el-form-item>

          <el-form-item label="故障分类">
            <div class="segmented">
              <button
                v-for="item in CATEGORY_OPTIONS"
                :key="item.value"
                type="button"
                class="segmented__item"
                :class="{ 'is-active': form.category === item.value }"
                @click="form.category = item.value"
              >
                {{ item.label }}
              </button>
            </div>
          </el-form-item>

          <el-form-item label="紧急度">
            <div class="segmented segmented--urgency">
              <button
                v-for="item in URGENCY_OPTIONS"
                :key="item.value"
                type="button"
                class="segmented__item"
                :class="[`segmented__item--${item.value.toLowerCase()}`, { 'is-active': form.urgency === item.value }]"
                @click="form.urgency = item.value"
              >
                {{ item.label }}
              </button>
            </div>
            <p class="field-hint">{{ urgencyHint }}</p>
          </el-form-item>

          <el-form-item :label="`现场图片（${attachments.length}/${MAX_ATTACHMENTS}）`">
            <el-upload
              v-model:file-list="fileList"
              list-type="picture-card"
              accept=".jpg,.jpeg,.png"
              :limit="MAX_ATTACHMENTS"
              :http-request="handleUpload"
              :before-upload="beforeUpload"
              :on-remove="handleRemove"
              :on-exceed="handleExceed"
            >
              <el-icon><Plus /></el-icon>
            </el-upload>
            <p class="field-hint">
              支持 jpg / png，单张不超过 10MB；先上传为附件，提交时自动关联工单。
            </p>
          </el-form-item>
        </el-form>

        <div class="form-actions">
          <el-button @click="cancel">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submit">提交工单</el-button>
        </div>
      </section>

      <aside class="side">
        <section class="sd-card side-card">
          <div class="sd-card__header">
            <h2 class="sd-card__title">AI 摘要</h2>
            <span class="side-card__badge">接口未接入</span>
          </div>
          <p class="side-card__placeholder">功能实现中ing，敬请期待~</p>
          <p class="side-card__desc">
            AI 分类定级（AI-01）尚未接入。当前提交后直接使用你选择的分类与紧急度落库，不阻塞建单。
          </p>
        </section>

        <section class="sd-card side-card">
          <h2 class="sd-card__title">SLA 预览</h2>
          <dl class="sla-list">
            <div>
              <dt>响应时限</dt>
              <dd>{{ humanizeMinutes(slaPreview.responseMinutes) }}</dd>
            </div>
            <div>
              <dt>解决时限</dt>
              <dd>{{ humanizeMinutes(slaPreview.resolveMinutes) }}</dd>
            </div>
            <div>
              <dt>适用策略</dt>
              <dd>{{ slaPreview.name }}</dd>
            </div>
          </dl>
          <p class="side-card__desc">
            按产品文档 7.3 的默认策略预览；实际 deadline 由服务端在提交时按启用的策略计算。
          </p>
        </section>

        <section class="sd-card side-card">
          <h2 class="sd-card__title">提示</h2>
          <p class="side-card__desc">
            描述越具体，派单越准确：写清设备型号、故障现象、发生时间与影响范围。附件上传失败不影响建单。
          </p>
        </section>

        <section class="sd-card side-card">
          <h2 class="sd-card__title">也可以让 Agent 建单</h2>
          <p class="side-card__desc">功能实现中ing，敬请期待~</p>
          <el-button class="side-card__agent" disabled>打开 Agent</el-button>
        </section>
      </aside>
    </div>

    <el-dialog v-model="successVisible" title="工单创建成功" width="460px" :close-on-click-modal="false">
      <div v-if="createdTicket" class="success">
        <p class="success__no mono">{{ createdTicket.ticketNo }}</p>
        <p class="success__title">{{ createdTicket.title }}</p>
        <dl class="success__sla">
          <div>
            <dt>响应截止</dt>
            <dd>{{ formatDateTime(createdTicket.sla?.responseDeadline) }}</dd>
          </div>
          <div>
            <dt>解决截止</dt>
            <dd>{{ formatDateTime(createdTicket.sla?.resolveDeadline) }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>待派单</dd>
          </div>
        </dl>
      </div>
      <template #footer>
        <el-button @click="createAnother">继续建单</el-button>
        <el-button @click="goDetail">查看详情</el-button>
        <el-button type="primary" @click="cancel">返回列表</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ticket-create {
  display: block;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.form-col {
  min-width: 0;
}

.required {
  color: var(--sd-red);
  font-style: normal;
}

.segmented {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.segmented__item {
  height: 32px;
  padding: 0 16px;
  border-radius: 8px;
  border: 1px solid var(--sd-line);
  background: #fff;
  color: var(--sd-text-secondary);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.16s ease;
}

.segmented__item:hover {
  border-color: #b9c8e4;
  color: var(--sd-text);
}

.segmented__item.is-active {
  border-color: var(--sd-primary);
  background: var(--sd-primary);
  color: #fff;
  font-weight: 500;
}

.segmented--urgency .segmented__item {
  min-width: 64px;
  font-family: Consolas, 'Courier New', monospace;
  font-weight: 600;
}

.segmented--urgency .segmented__item--p0.is-active {
  background: var(--sd-red);
  border-color: var(--sd-red);
}

.segmented--urgency .segmented__item--p1.is-active {
  background: var(--sd-orange);
  border-color: var(--sd-orange);
}

.segmented--urgency .segmented__item--p2.is-active {
  background: var(--sd-primary);
  border-color: var(--sd-primary);
}

.segmented--urgency .segmented__item--p3.is-active {
  background: #64748b;
  border-color: #64748b;
}

.field-hint {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--sd-text-muted);
  line-height: 1.6;
}

.manual-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.manual-field__input {
  width: 100%;
}

.manual-field__tip {
  font-size: 11px;
  color: var(--sd-text-muted);
  line-height: 1.5;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
  padding-top: 18px;
  border-top: 1px solid var(--sd-line);
}

/* 右侧信息栏 */
.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.side-card {
  padding: 18px 20px;
}

.side-card__badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--sd-orange-soft);
  color: var(--sd-orange);
  font-size: 10.5px;
}

.side-card__placeholder {
  margin: 4px 0 8px;
  font-size: 13px;
  color: var(--sd-primary);
}

.side-card__desc {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--sd-text-muted);
}

.sla-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sla-list > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.sla-list dt {
  font-size: 12px;
  color: var(--sd-text-muted);
}

.sla-list dd {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--sd-text);
}

.side-card__agent {
  width: 100%;
  margin-top: 12px;
}

/* 成功弹窗 */
.success {
  text-align: center;
  padding: 4px 0 8px;
}

.success__no {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sd-primary);
}

.success__title {
  margin: 6px 0 16px;
  font-size: 13px;
  color: var(--sd-text-secondary);
}

.success__sla {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
  background: #f7f9fc;
  border-radius: 10px;
  padding: 12px 14px;
}

.success__sla > div {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.success__sla dt {
  color: var(--sd-text-muted);
}

.success__sla dd {
  margin: 0;
  color: var(--sd-text);
}

@media (max-width: 1200px) {
  .form-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
