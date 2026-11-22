<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { pageTickets } from '@/api/tickets'
import StatusTag from '@/components/StatusTag.vue'
import UrgencyTag from '@/components/UrgencyTag.vue'
import SlaRemaining from '@/components/SlaRemaining.vue'
import { CATEGORY_LABELS, CATEGORY_OPTIONS, STATUS_OPTIONS, URGENCY_OPTIONS } from '@/utils/dict'
import { formatShortDateTime } from '@/utils/format'

/** 允许的每页条数，与开发文档 P-03 的 10/20/50 一致。 */
const PAGE_SIZES = [10, 20, 50]

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const filters = reactive({
  keyword: '',
  status: '',
  urgency: '',
  category: '',
  mine: false,
  page: 1,
  size: 10
})

/** 当前页展示区间文案，例如「显示 1-10 条，共 128 条」。 */
const rangeText = computed(() => {
  if (total.value === 0) {
    return '暂无数据'
  }
  const start = (filters.page - 1) * filters.size + 1
  const end = Math.min(filters.page * filters.size, total.value)
  return `显示 ${start}-${end} 条，共 ${total.value} 条`
})

/**
 * 组装请求参数，空条件不传，避免后端把空串当非法枚举。
 *
 * @returns {object} 接口 2.1 的查询参数
 */
function buildParams() {
  const params = { page: filters.page, size: filters.size }
  const keyword = filters.keyword.trim()
  if (keyword) {
    params.keyword = keyword
  }
  if (filters.status) {
    params.status = filters.status
  }
  if (filters.urgency) {
    params.urgency = filters.urgency
  }
  if (filters.category) {
    params.category = filters.category
  }
  if (filters.mine) {
    params.mine = true
  }
  return params
}

/** 从 URL query 还原筛选条件，保证刷新与回退后条件不丢。 */
function applyQueryFromRoute() {
  const query = route.query
  filters.keyword = typeof query.keyword === 'string' ? query.keyword : ''
  filters.status = typeof query.status === 'string' ? query.status : ''
  filters.urgency = typeof query.urgency === 'string' ? query.urgency : ''
  filters.category = typeof query.category === 'string' ? query.category : ''
  filters.mine = query.mine === 'true'

  const page = Number(query.page)
  filters.page = Number.isInteger(page) && page > 0 ? page : 1

  const size = Number(query.size)
  filters.size = PAGE_SIZES.includes(size) ? size : 10
}

/** 把当前筛选条件写回 URL，只写有值的字段。 */
function syncQueryToRoute() {
  const params = buildParams()
  const query = {}
  Object.keys(params).forEach((key) => {
    query[key] = String(params[key])
  })
  router.replace({ path: '/tickets', query })
}

/** 拉取当前页数据。 */
async function load() {
  loading.value = true
  try {
    const data = await pageTickets(buildParams())
    rows.value = data?.records || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

/** 点击查询：回到第一页；条件未变时直接刷新，避免路由无变化而不触发请求。 */
function handleSearch() {
  filters.page = 1
  const nextParams = buildParams()
  const currentParams = buildParamsFromRoute()
  if (JSON.stringify(nextParams) === JSON.stringify(currentParams)) {
    load()
    return
  }
  syncQueryToRoute()
}

/**
 * 从 URL 还原一份参数对象，用于与当前条件比较。
 *
 * @returns {object} 查询参数
 */
function buildParamsFromRoute() {
  const query = route.query
  const params = {
    page: Number(query.page) || 1,
    size: PAGE_SIZES.includes(Number(query.size)) ? Number(query.size) : 10
  }
  if (query.keyword) {
    params.keyword = query.keyword
  }
  if (query.status) {
    params.status = query.status
  }
  if (query.urgency) {
    params.urgency = query.urgency
  }
  if (query.category) {
    params.category = query.category
  }
  if (query.mine === 'true') {
    params.mine = true
  }
  return params
}

/** 重置筛选条件并重新查询。 */
function handleReset() {
  filters.keyword = ''
  filters.status = ''
  filters.urgency = ''
  filters.category = ''
  filters.mine = false
  filters.page = 1
  filters.size = 10
  syncQueryToRoute()
}

/**
 * 页码或每页条数变化。
 *
 * @param {number} page 新页码
 * @param {number} size 新每页条数
 */
function handlePageChange(page, size) {
  filters.page = size !== filters.size ? 1 : page
  filters.size = size
  syncQueryToRoute()
}

/**
 * 行点击进入详情。
 *
 * @param {object} row 列表项
 */
function openDetail(row) {
  router.push(`/tickets/${row.id}`)
}

/**
 * 分类编码转中文。
 *
 * @param {string} category 分类编码
 * @returns {string} 中文名
 */
function categoryLabel(category) {
  return CATEGORY_LABELS[category] || category || '-'
}

/**
 * 表格行样式：整行可点，给出可点击光标。
 *
 * @returns {string} 行类名
 */
function rowClassName() {
  return 'is-clickable'
}

watch(
  () => route.query,
  () => {
    applyQueryFromRoute()
    load()
  }
)

onMounted(() => {
  applyQueryFromRoute()
  load()
})
</script>

<template>
  <div class="ticket-list">
    <section class="sd-card filter">
      <div class="filter__fields">
        <el-input
          v-model="filters.keyword"
          class="filter__keyword"
          placeholder="搜索工单号 / 标题"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-select
          v-model="filters.status"
          class="filter__select"
          placeholder="全部状态"
          clearable
          @change="handleSearch"
        >
          <el-option
            v-for="item in STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <el-select
          v-model="filters.urgency"
          class="filter__select filter__select--sm"
          placeholder="紧急度"
          clearable
          @change="handleSearch"
        >
          <el-option
            v-for="item in URGENCY_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <el-select
          v-model="filters.category"
          class="filter__select"
          placeholder="全部分类"
          clearable
          @change="handleSearch"
        >
          <el-option
            v-for="item in CATEGORY_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <el-checkbox v-model="filters.mine" class="filter__mine" @change="handleSearch">
          只看与我相关
        </el-checkbox>

        <el-button type="primary" class="filter__search" @click="handleSearch">查询</el-button>
        <el-button class="filter__reset" @click="handleReset">重置</el-button>
      </div>

      <span class="filter__total">共 {{ total }} 张</span>
    </section>

    <section class="sd-card table-card">
      <el-table
        v-loading="loading"
        :data="rows"
        :row-class-name="rowClassName"
        style="width: 100%"
        @row-click="openDetail"
      >
        <el-table-column label="工单号" width="186">
          <template #default="{ row }">
            <div class="cell-ticket-no">
              <span class="mono">{{ row.ticketNo }}</span>
              <em>创建于 {{ formatShortDateTime(row.createdAt) }}</em>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="标题" min-width="180">
          <template #default="{ row }">
            <span class="cell-title" :title="row.title">{{ row.title }}</span>
          </template>
        </el-table-column>

        <el-table-column label="客户" width="164" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-customer">{{ row.customerName || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="104">
          <template #default="{ row }">
            <span class="cell-category">{{ categoryLabel(row.category) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="紧急度" width="84">
          <template #default="{ row }">
            <UrgencyTag :urgency="row.urgency" />
          </template>
        </el-table-column>

        <el-table-column label="状态" width="98">
          <template #default="{ row }">
            <StatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="工程师" width="96">
          <template #default="{ row }">
            <span class="cell-assignee" :class="{ 'is-empty': !row.assigneeName }">
              {{ row.assigneeName || '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="SLA 剩余" width="110">
          <template #default="{ row }">
            <SlaRemaining
              :minutes="row.slaRemainingMinutes"
              :breached="row.slaBreached"
              :status="row.status"
            />
          </template>
        </el-table-column>

        <template #empty>
          <div class="table-empty">
            <p>{{ loading ? '加载中…' : '还没有符合条件的工单' }}</p>
            <p v-if="!loading && filters.keyword" class="table-empty__hint">
              关键字只匹配工单号与标题，不含客户名；按客户查找请到「客户与设备 → 客户档案」。
            </p>
            <el-button v-if="!loading" type="primary" plain @click="router.push('/tickets/new')">
              新建工单
            </el-button>
          </div>
        </template>
      </el-table>

      <div class="table-footer">
        <span class="table-footer__range">{{ rangeText }}</span>
        <el-pagination
          :current-page="filters.page"
          :page-size="filters.size"
          :page-sizes="PAGE_SIZES"
          :total="total"
          layout="sizes, prev, pager, next"
          background
          size="small"
          @current-change="(page) => handlePageChange(page, filters.size)"
          @size-change="(size) => handlePageChange(filters.page, size)"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.ticket-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 筛选区 */
.filter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
}

.filter__fields {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}

.filter__keyword {
  width: 300px;
}

.filter__select {
  width: 132px;
}

.filter__select--sm {
  width: 108px;
}

.filter__mine {
  margin-left: 2px;
  margin-right: 2px;
}

.filter__search,
.filter__reset {
  height: 32px;
}

.filter__search {
  padding: 0 22px;
}

.filter__total {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--sd-text-muted);
}

/* 表格 */
.table-card {
  padding: 4px 20px 16px;
}

.table-card :deep(.el-table__row.is-clickable) {
  cursor: pointer;
}

.cell-ticket-no {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.cell-ticket-no span {
  font-size: 12.5px;
  color: #1f2a3a;
}

.cell-ticket-no em {
  font-style: normal;
  font-size: 10.5px;
  color: var(--sd-text-muted);
}

.cell-title {
  font-weight: 500;
  color: var(--sd-text);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-customer,
.cell-category {
  color: var(--sd-text-secondary);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-assignee {
  color: var(--sd-text);
}

.cell-assignee.is-empty {
  color: var(--sd-text-muted);
}

.table-empty {
  padding: 34px 0;
  color: var(--sd-text-muted);
}

.table-empty p {
  margin: 0 0 12px;
}

.table-empty__hint {
  font-size: 11.5px;
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

@media (max-width: 1280px) {
  .filter {
    flex-direction: column;
    align-items: flex-start;
  }

  .filter__total {
    align-self: flex-end;
  }
}
</style>
