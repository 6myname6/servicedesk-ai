<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { customerApi, deviceApi } from '@/api/admin'
import {
  DEVICE_STATUS_LABELS,
  DEVICE_STATUS_OPTIONS,
  LEVEL_LABELS,
  LEVEL_OPTIONS,
  WARRANTY_STATUS_LABELS
} from '@/utils/dict'

/** 分页默认每页条数。 */
const DEFAULT_SIZE = 10

const activeTab = ref('customer')

/* ---------------- 客户档案 ---------------- */

const customerLoading = ref(false)
const customerRows = ref([])
const customerPage = reactive({ page: 1, size: DEFAULT_SIZE, total: 0 })
const customerKeyword = ref('')

const customerDialogVisible = ref(false)
const customerDialogMode = ref('create')
const customerSaving = ref(false)
const customerFormRef = ref(null)
const editingCustomerId = ref(null)

const customerForm = reactive({
  name: '',
  contact: '',
  phone: '',
  address: '',
  level: 'NORMAL',
  remark: ''
})

/** 客户表单校验，长度口径与 CustomerCreateRequest 一致。 */
const customerRules = {
  name: [
    { required: true, message: '请输入客户名', trigger: 'blur' },
    { max: 64, message: '客户名不能超过 64 位', trigger: 'blur' }
  ],
  contact: [{ max: 32, message: '联系人不能超过 32 位', trigger: 'blur' }],
  phone: [{ max: 20, message: '电话不能超过 20 位', trigger: 'blur' }],
  address: [{ max: 255, message: '地址不能超过 255 位', trigger: 'blur' }],
  remark: [{ max: 255, message: '备注不能超过 255 位', trigger: 'blur' }]
}

/* ---------------- 设备档案 ---------------- */

const deviceLoading = ref(false)
const deviceRows = ref([])
const devicePage = reactive({ page: 1, size: DEFAULT_SIZE, total: 0 })
const deviceKeyword = ref('')
const deviceCustomerFilter = ref(null)
const customerOptions = ref([])

const deviceDialogVisible = ref(false)
const deviceDialogMode = ref('create')
const deviceSaving = ref(false)
const deviceFormRef = ref(null)
const editingDeviceId = ref(null)

const deviceForm = reactive({
  customerId: null,
  model: '',
  serialNo: '',
  purchaseDate: null,
  warrantyEnd: null,
  location: '',
  status: 'ACTIVE'
})

/** 设备表单校验，序列号必填且唯一由后端兜底（重复返回 409）。 */
const deviceRules = {
  customerId: [{ required: true, message: '请选择所属客户', trigger: 'change' }],
  serialNo: [
    { required: true, message: '请输入序列号', trigger: 'blur' },
    { max: 64, message: '序列号不能超过 64 位', trigger: 'blur' }
  ],
  model: [{ max: 64, message: '型号不能超过 64 位', trigger: 'blur' }],
  location: [{ max: 255, message: '位置不能超过 255 位', trigger: 'blur' }]
}

/** 客户分页区间文案。 */
function customerRangeText() {
  if (customerPage.total === 0) {
    return '暂无数据'
  }
  const start = (customerPage.page - 1) * customerPage.size + 1
  const end = Math.min(customerPage.page * customerPage.size, customerPage.total)
  return `显示 ${start}-${end} 条，共 ${customerPage.total} 条`
}

/** 设备分页区间文案。 */
function deviceRangeText() {
  if (devicePage.total === 0) {
    return '暂无数据'
  }
  const start = (devicePage.page - 1) * devicePage.size + 1
  const end = Math.min(devicePage.page * devicePage.size, devicePage.total)
  return `显示 ${start}-${end} 条，共 ${devicePage.total} 条`
}

/** 拉取客户列表。 */
async function loadCustomers() {
  customerLoading.value = true
  try {
    const data = await customerApi.page({
      keyword: customerKeyword.value.trim() || undefined,
      page: customerPage.page,
      size: customerPage.size
    })
    customerRows.value = data?.records || []
    customerPage.total = data?.total || 0
  } finally {
    customerLoading.value = false
  }
}

/** 拉取设备列表。 */
async function loadDevices() {
  deviceLoading.value = true
  try {
    const data = await deviceApi.page({
      keyword: deviceKeyword.value.trim() || undefined,
      customerId: deviceCustomerFilter.value || undefined,
      page: devicePage.page,
      size: devicePage.size
    })
    deviceRows.value = data?.records || []
    devicePage.total = data?.total || 0
  } finally {
    deviceLoading.value = false
  }
}

/** 载入客户下拉选项（设备表单与筛选共用，取前 100 条）。 */
async function loadCustomerOptions() {
  const data = await customerApi.page({ page: 1, size: 100 })
  customerOptions.value = data?.records || []
}

/** 客户查询：回到第一页。 */
function searchCustomers() {
  customerPage.page = 1
  loadCustomers()
}

/** 重置客户筛选条件。 */
function resetCustomerSearch() {
  customerKeyword.value = ''
  searchCustomers()
}

/** 设备查询：回到第一页。 */
function searchDevices() {
  devicePage.page = 1
  loadDevices()
}

/** 重置设备筛选条件。 */
function resetDeviceSearch() {
  deviceKeyword.value = ''
  deviceCustomerFilter.value = null
  searchDevices()
}

/**
 * 客户分页变化。
 *
 * @param {number} page 页码
 * @param {number} size 每页条数
 */
function changeCustomerPage(page, size) {
  customerPage.page = size !== customerPage.size ? 1 : page
  customerPage.size = size
  loadCustomers()
}

/**
 * 设备分页变化。
 *
 * @param {number} page 页码
 * @param {number} size 每页条数
 */
function changeDevicePage(page, size) {
  devicePage.page = size !== devicePage.size ? 1 : page
  devicePage.size = size
  loadDevices()
}

/** 重置客户表单。 */
function resetCustomerForm() {
  customerForm.name = ''
  customerForm.contact = ''
  customerForm.phone = ''
  customerForm.address = ''
  customerForm.level = 'NORMAL'
  customerForm.remark = ''
  editingCustomerId.value = null
}

/** 打开新增客户弹窗。 */
function openCustomerCreate() {
  resetCustomerForm()
  customerDialogMode.value = 'create'
  customerDialogVisible.value = true
}

/**
 * 打开编辑客户弹窗，回填完整字段（后端 update 语义为整体覆盖）。
 *
 * @param {object} row 客户行
 */
function openCustomerEdit(row) {
  customerDialogMode.value = 'edit'
  editingCustomerId.value = row.id
  customerForm.name = row.name || ''
  customerForm.contact = row.contact || ''
  customerForm.phone = row.phone || ''
  customerForm.address = row.address || ''
  customerForm.level = row.level || 'NORMAL'
  customerForm.remark = row.remark || ''
  customerDialogVisible.value = true
}

/** 提交客户表单。 */
async function submitCustomer() {
  if (customerSaving.value) {
    return
  }
  const valid = await customerFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  customerSaving.value = true
  try {
    const payload = {
      name: customerForm.name.trim(),
      contact: customerForm.contact || null,
      phone: customerForm.phone || null,
      address: customerForm.address || null,
      level: customerForm.level,
      remark: customerForm.remark || null
    }
    if (customerDialogMode.value === 'create') {
      await customerApi.create(payload)
      ElMessage.success('客户已创建')
    } else {
      await customerApi.update(editingCustomerId.value, payload)
      ElMessage.success('客户已更新')
    }
    customerDialogVisible.value = false
    await Promise.all([loadCustomers(), loadCustomerOptions()])
  } finally {
    customerSaving.value = false
  }
}

/** 重置设备表单。 */
function resetDeviceForm() {
  deviceForm.customerId = deviceCustomerFilter.value || null
  deviceForm.model = ''
  deviceForm.serialNo = ''
  deviceForm.purchaseDate = null
  deviceForm.warrantyEnd = null
  deviceForm.location = ''
  deviceForm.status = 'ACTIVE'
  editingDeviceId.value = null
}

/** 打开新增设备弹窗。 */
function openDeviceCreate() {
  resetDeviceForm()
  deviceDialogMode.value = 'create'
  deviceDialogVisible.value = true
}

/**
 * 打开编辑设备弹窗。
 *
 * @param {object} row 设备行
 */
function openDeviceEdit(row) {
  deviceDialogMode.value = 'edit'
  editingDeviceId.value = row.id
  deviceForm.customerId = row.customerId
  deviceForm.model = row.model || ''
  deviceForm.serialNo = row.serialNo || ''
  deviceForm.purchaseDate = row.purchaseDate || null
  deviceForm.warrantyEnd = row.warrantyEnd || null
  deviceForm.location = row.location || ''
  deviceForm.status = row.status || 'ACTIVE'
  deviceDialogVisible.value = true
}

/** 提交设备表单。 */
async function submitDevice() {
  if (deviceSaving.value) {
    return
  }
  const valid = await deviceFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  deviceSaving.value = true
  try {
    const isCreate = deviceDialogMode.value === 'create'
    const payload = {
      customerId: deviceForm.customerId,
      model: deviceForm.model || null,
      serialNo: deviceForm.serialNo.trim(),
      // 新增时空值不带字段；编辑时显式 null 表示清空
      purchaseDate: isCreate ? deviceForm.purchaseDate || undefined : deviceForm.purchaseDate || null,
      warrantyEnd: isCreate ? deviceForm.warrantyEnd || undefined : deviceForm.warrantyEnd || null,
      location: deviceForm.location || null,
      status: deviceForm.status
    }
    if (isCreate) {
      await deviceApi.create(payload)
      ElMessage.success('设备已创建')
    } else {
      await deviceApi.update(editingDeviceId.value, payload)
      ElMessage.success('设备已更新')
    }
    deviceDialogVisible.value = false
    await loadDevices()
  } finally {
    deviceSaving.value = false
  }
}

/**
 * 切换 Tab 时懒加载对应列表。
 *
 * @param {string} name Tab 名
 */
function handleTabChange(name) {
  if (name === 'device' && deviceRows.value.length === 0) {
    loadDevices()
  }
  if (name === 'customer' && customerRows.value.length === 0) {
    loadCustomers()
  }
}

onMounted(() => {
  loadCustomers()
  loadCustomerOptions()
})
</script>

<template>
  <section class="sd-card assets">
    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <el-tab-pane label="客户档案" name="customer">
        <div class="toolbar">
          <div class="toolbar__left">
            <el-input
              v-model="customerKeyword"
              class="toolbar__keyword"
              placeholder="搜索客户名 / 电话"
              clearable
              @keyup.enter="searchCustomers"
              @clear="searchCustomers"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button type="primary" @click="searchCustomers">查询</el-button>
            <el-button @click="resetCustomerSearch">重置</el-button>
          </div>
          <el-button type="primary" plain @click="openCustomerCreate">
            <el-icon><Plus /></el-icon>
            <span>新增客户</span>
          </el-button>
        </div>

        <el-table v-loading="customerLoading" :data="customerRows" style="width: 100%">
          <el-table-column prop="name" label="客户名" min-width="200">
            <template #default="{ row }">
              <span class="cell-strong">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="等级" width="104">
            <template #default="{ row }">
              <span class="level-tag" :class="{ 'level-tag--key': row.level === 'KEY' }">
                {{ LEVEL_LABELS[row.level] || row.level }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="contact" label="联系人" width="110">
            <template #default="{ row }">{{ row.contact || '—' }}</template>
          </el-table-column>
          <el-table-column prop="phone" label="联系电话" width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.phone || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.address || '—' }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openCustomerEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="table-empty">{{ customerLoading ? '加载中…' : '还没有客户档案' }}</div>
          </template>
        </el-table>

        <div class="table-footer">
          <span class="table-footer__range">{{ customerRangeText() }}</span>
          <el-pagination
            :current-page="customerPage.page"
            :page-size="customerPage.size"
            :page-sizes="[10, 20, 50]"
            :total="customerPage.total"
            layout="sizes, prev, pager, next"
            background
            size="small"
            @current-change="(page) => changeCustomerPage(page, customerPage.size)"
            @size-change="(size) => changeCustomerPage(customerPage.page, size)"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="设备档案" name="device">
        <div class="toolbar">
          <div class="toolbar__left">
            <el-input
              v-model="deviceKeyword"
              class="toolbar__keyword"
              placeholder="搜索型号 / 序列号"
              clearable
              @keyup.enter="searchDevices"
              @clear="searchDevices"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-select
              v-model="deviceCustomerFilter"
              class="toolbar__select"
              placeholder="全部客户"
              clearable
              filterable
              @change="searchDevices"
            >
              <el-option
                v-for="item in customerOptions"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
            <el-button type="primary" @click="searchDevices">查询</el-button>
            <el-button @click="resetDeviceSearch">重置</el-button>
          </div>
          <el-button type="primary" plain @click="openDeviceCreate">
            <el-icon><Plus /></el-icon>
            <span>新增设备</span>
          </el-button>
        </div>

        <el-table v-loading="deviceLoading" :data="deviceRows" style="width: 100%">
          <el-table-column prop="model" label="设备型号" min-width="150">
            <template #default="{ row }">
              <span class="cell-strong">{{ row.model || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="serialNo" label="序列号" width="170">
            <template #default="{ row }">
              <span class="mono">{{ row.serialNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="customerName" label="所属客户" min-width="170">
            <template #default="{ row }">{{ row.customerName || '—' }}</template>
          </el-table-column>
          <el-table-column label="保修状态" width="104">
            <template #default="{ row }">
              <span
                class="warranty"
                :class="{
                  'warranty--ok': row.warrantyStatus === 'IN_WARRANTY',
                  'warranty--expired': row.warrantyStatus === 'EXPIRED'
                }"
              >
                {{ WARRANTY_STATUS_LABELS[row.warrantyStatus] || row.warrantyStatus }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="保修到期" width="112">
            <template #default="{ row }">
              <span class="mono">{{ row.warrantyEnd || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="location" label="安装位置" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.location || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <span class="device-status" :class="{ 'device-status--scrapped': row.status === 'SCRAPPED' }">
                {{ DEVICE_STATUS_LABELS[row.status] || row.status }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDeviceEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="table-empty">{{ deviceLoading ? '加载中…' : '还没有设备档案' }}</div>
          </template>
        </el-table>

        <div class="table-footer">
          <span class="table-footer__range">{{ deviceRangeText() }}</span>
          <el-pagination
            :current-page="devicePage.page"
            :page-size="devicePage.size"
            :page-sizes="[10, 20, 50]"
            :total="devicePage.total"
            layout="sizes, prev, pager, next"
            background
            size="small"
            @current-change="(page) => changeDevicePage(page, devicePage.size)"
            @size-change="(size) => changeDevicePage(devicePage.page, size)"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 客户表单 -->
    <el-dialog
      v-model="customerDialogVisible"
      :title="customerDialogMode === 'create' ? '新增客户' : '编辑客户'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="customerFormRef" :model="customerForm" :rules="customerRules" label-width="76px">
        <el-form-item label="客户名" prop="name">
          <el-input v-model="customerForm.name" maxlength="64" placeholder="请输入客户名" />
        </el-form-item>
        <el-form-item label="等级">
          <el-select v-model="customerForm.level" class="full-width">
            <el-option
              v-for="item in LEVEL_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人" prop="contact">
          <el-input v-model="customerForm.contact" maxlength="32" placeholder="选填" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="customerForm.phone" maxlength="20" placeholder="选填" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="customerForm.address" maxlength="255" placeholder="选填" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="customerForm.remark"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="customerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="customerSaving" @click="submitCustomer">保存</el-button>
      </template>
    </el-dialog>

    <!-- 设备表单 -->
    <el-dialog
      v-model="deviceDialogVisible"
      :title="deviceDialogMode === 'create' ? '新增设备' : '编辑设备'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="deviceFormRef" :model="deviceForm" :rules="deviceRules" label-width="86px">
        <el-form-item label="所属客户" prop="customerId">
          <el-select
            v-model="deviceForm.customerId"
            class="full-width"
            filterable
            placeholder="请选择客户"
          >
            <el-option
              v-for="item in customerOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="设备型号" prop="model">
          <el-input v-model="deviceForm.model" maxlength="64" placeholder="例如 AC-2000" />
        </el-form-item>
        <el-form-item label="序列号" prop="serialNo">
          <el-input v-model="deviceForm.serialNo" maxlength="64" placeholder="全局唯一，例如 SN-2024-001" />
        </el-form-item>
        <el-form-item label="购买日期">
          <el-date-picker
            v-model="deviceForm.purchaseDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选填"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="保修到期">
          <el-date-picker
            v-model="deviceForm.warrantyEnd"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选填，留空表示未登记保修"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="安装位置" prop="location">
          <el-input v-model="deviceForm.location" maxlength="255" placeholder="选填" />
        </el-form-item>
        <el-form-item label="设备状态">
          <el-select v-model="deviceForm.status" class="full-width">
            <el-option
              v-for="item in DEVICE_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deviceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="deviceSaving" @click="submitDevice">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.assets {
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
  width: 240px;
}

.toolbar__select {
  width: 180px;
}

.cell-strong {
  font-weight: 500;
  color: var(--sd-text);
}

.level-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 5px;
  background: #eef1f6;
  color: #64748b;
  font-size: 11px;
}

.level-tag--key {
  background: var(--sd-teal);
  color: #fff;
}

.warranty {
  font-size: 12px;
  color: var(--sd-text-muted);
}

.warranty--ok {
  color: var(--sd-green);
}

.warranty--expired {
  color: var(--sd-orange);
}

.device-status {
  font-size: 12px;
  color: var(--sd-green);
}

.device-status--scrapped {
  color: var(--sd-text-muted);
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
