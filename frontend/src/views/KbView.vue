<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import PrototypeBadge from '@/components/PrototypeBadge.vue'
import { demoKbArticles } from '@/mock/prototype'
import { CATEGORY_LABELS, CATEGORY_OPTIONS } from '@/utils/dict'
import { formatShortDateTime } from '@/utils/format'

/**
 * 知识库文章状态展示配置。
 *
 * <p>状态取值与开发文档 P-06 一致：PENDING 灰、PARSING/INDEXING 蓝、
 * READY 绿、FAILED 红（悬浮显示失败原因）。</p>
 */
const KB_STATUS = {
  PENDING: { label: '待解析', variant: 'pending', hint: '等待解析任务调度' },
  PARSING: { label: '解析中', variant: 'running', hint: '正在抽取文档文本' },
  INDEXING: { label: '索引中', variant: 'running', hint: '正在切分并写入向量库' },
  READY: { label: '已就绪', variant: 'ready', hint: '可被 AI 知识推荐检索' },
  FAILED: { label: '失败', variant: 'failed', hint: '解析或索引失败' }
}

/** 状态筛选项。 */
const STATUS_FILTERS = [
  { value: '', label: '全部状态' },
  { value: 'READY', label: '已就绪' },
  { value: 'PENDING', label: '待解析' },
  { value: 'INDEXING', label: '索引中' },
  { value: 'FAILED', label: '失败' }
]

const route = useRoute()
const router = useRouter()

/** 文章列表，原型阶段保存在内存中。 */
const articles = ref(demoKbArticles())

const keyword = ref('')
const statusFilter = ref('')

const drawerVisible = ref(false)
const saving = ref(false)

/** 编辑抽屉表单。 */
const form = reactive({
  id: null,
  title: '',
  category: 'OTHER',
  content: ''
})

/** 按关键词与状态过滤后的文章。 */
const filteredArticles = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return articles.value.filter((item) => {
    const matchStatus = !statusFilter.value || item.status === statusFilter.value
    const matchKeyword =
      !text ||
      item.title.toLowerCase().includes(text) ||
      (CATEGORY_LABELS[item.category] || '').includes(text)
    return matchStatus && matchKeyword
  })
})

/** 统计：文章总数。 */
const totalCount = computed(() => articles.value.length)

/** 统计：已就绪数量。 */
const readyCount = computed(() => articles.value.filter((item) => item.status === 'READY').length)

/** 统计：处理中数量（解析中 / 索引中）。 */
const runningCount = computed(() =>
  articles.value.filter((item) => ['PARSING', 'INDEXING'].includes(item.status)).length
)

/** 统计：失败数量。 */
const failedCount = computed(() => articles.value.filter((item) => item.status === 'FAILED').length)

/** 抽屉标题。 */
const drawerTitle = computed(() => (form.id ? '编辑知识文章' : '新建知识文章'))

/** 是否为新建。 */
const isCreating = computed(() => form.id === null)

/**
 * 取状态展示配置。
 *
 * @param {string} status 状态编码
 * @returns {{label: string, variant: string, hint: string}} 展示配置
 */
function statusMeta(status) {
  return KB_STATUS[status] || { label: status, variant: 'pending', hint: '' }
}

/** 把编辑表单重置为空。 */
function resetForm() {
  form.id = null
  form.title = ''
  form.category = 'OTHER'
  form.content = ''
}

/**
 * 用文章数据回填表单。
 *
 * @param {object} article 文章
 */
function fillForm(article) {
  form.id = article.id
  form.title = article.title
  form.category = article.category
  form.content = article.content
}

/**
 * 根据路由同步抽屉：/kb 关闭，/kb/new 新建，/kb/:id 编辑。
 *
 * <p>把抽屉状态放在 URL 上，便于刷新与回退，符合「所有列表记住筛选条件」的通用规则。</p>
 */
function syncFromRoute() {
  if (route.name === 'kb') {
    drawerVisible.value = false
    return
  }

  if (route.params.id === 'new') {
    resetForm()
    drawerVisible.value = true
    return
  }

  const id = Number(route.params.id)
  const article = articles.value.find((item) => item.id === id)
  if (!article) {
    ElMessage.warning('文章不存在或已被删除')
    router.replace('/kb')
    return
  }
  fillForm(article)
  drawerVisible.value = true
}

/** 新建文章：跳路由，由 syncFromRoute 打开抽屉。 */
function openCreate() {
  router.push('/kb/new')
}

/**
 * 打开编辑。
 *
 * @param {object} article 文章
 */
function openEdit(article) {
  router.push(`/kb/${article.id}`)
}

/** 关闭抽屉并回到列表路由。 */
function closeDrawer() {
  router.push('/kb')
}

/**
 * 保存文章。
 *
 * @param {boolean} indexAfter 是否保存后立即重建索引
 */
async function save(indexAfter = false) {
  if (!form.title.trim()) {
    ElMessage.warning('请输入文章标题')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请输入文章正文')
    return
  }

  saving.value = true
  try {
    const nextStatus = indexAfter ? 'INDEXING' : 'PENDING'
    let target = null

    if (isCreating.value) {
      const nextId = Math.max(0, ...articles.value.map((item) => item.id)) + 1
      target = {
        id: nextId,
        title: form.title.trim(),
        category: form.category,
        status: nextStatus,
        chunks: 0,
        author: '当前登录人',
        updatedAt: new Date(),
        content: form.content
      }
      articles.value.unshift(target)
    } else {
      const article = articles.value.find((item) => item.id === form.id)
      if (article) {
        article.title = form.title.trim()
        article.category = form.category
        article.content = form.content
        article.updatedAt = new Date()
        article.status = nextStatus
        target = article
      }
    }

    closeDrawer()

    if (indexAfter && target) {
      ElMessage.info('已保存并提交索引任务')
      window.setTimeout(() => {
        target.status = 'READY'
        target.chunks = Math.max(target.chunks, 10)
        ElMessage.success(`《${target.title}》索引完成`)
      }, 4000)
    } else {
      ElMessage.success('文章已保存，正文变更后需重建索引才会参与 AI 推荐')
    }
  } finally {
    saving.value = false
  }
}

/**
 * 重建索引：二次确认后进入索引中，原型用定时器模拟完成。
 *
 * @param {object} article 文章
 */
async function rebuild(article) {
  try {
    await ElMessageBox.confirm(
      `将重新解析并索引《${article.title}》，索引期间不可重复触发。确定继续吗？`,
      '重建索引',
      { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  article.status = 'INDEXING'
  ElMessage.info('索引任务已提交，完成后状态自动更新')
  window.setTimeout(() => {
    if (article.status === 'INDEXING') {
      article.status = 'READY'
      article.chunks = Math.max(article.chunks, 8)
      article.updatedAt = new Date()
      ElMessage.success(`《${article.title}》索引完成，共 ${article.chunks} 个片段`)
    }
  }, 4000)
}

/**
 * 删除文章。
 *
 * @param {object} article 文章
 */
async function remove(article) {
  try {
    await ElMessageBox.confirm(
      `删除后该文章不再参与 AI 知识推荐，历史引用记录保留。确定删除《${article.title}》吗？`,
      '删除知识文章',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  articles.value = articles.value.filter((item) => item.id !== article.id)
  ElMessage.success('已删除')
}

/** 上传文件：原型只提示解析动作，不真正上传。 */
function handleUpload(file) {
  ElMessage.info(`原型演示：已选择《${file.name}》，保存并索引时解析正文`)
}

watch(() => [route.name, route.params.id], syncFromRoute, { immediate: true })
</script>

<template>
  <div class="kb">
    <div class="kb__head">
      <div>
        <h1 class="kb__title">
          知识库
          <PrototypeBadge />
        </h1>
        <p class="kb__desc">沉淀故障处理经验，供 AI 知识推荐与坐席检索；正文变更后需重建索引。</p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新建文章
      </el-button>
    </div>

    <!-- 概览 -->
    <div class="kb__stats">
      <div class="kb-stat">
        <span class="kb-stat__label">文章总数</span>
        <strong>{{ totalCount }}</strong>
      </div>
      <div class="kb-stat">
        <span class="kb-stat__label">已就绪</span>
        <strong class="kb-stat__value--green">{{ readyCount }}</strong>
      </div>
      <div class="kb-stat">
        <span class="kb-stat__label">处理中</span>
        <strong class="kb-stat__value--blue">{{ runningCount }}</strong>
      </div>
      <div class="kb-stat">
        <span class="kb-stat__label">索引失败</span>
        <strong class="kb-stat__value--red">{{ failedCount }}</strong>
      </div>
    </div>

    <section class="sd-card">
      <div class="kb__filters">
        <el-input v-model="keyword" class="kb__search" placeholder="搜索标题或分类" clearable>
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-select v-model="statusFilter" class="kb__status" placeholder="全部状态">
          <el-option
            v-for="item in STATUS_FILTERS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <span class="kb__count">共 {{ filteredArticles.length }} 篇</span>
      </div>

      <el-table :data="filteredArticles" style="width: 100%">
        <el-table-column label="文章" min-width="300">
          <template #default="{ row }">
            <div class="kb-cell">
              <span class="kb-cell__title">{{ row.title }}</span>
              <em class="kb-cell__meta">
                {{ CATEGORY_LABELS[row.category] || row.category }} · {{ row.author }} 更新于
                {{ formatShortDateTime(row.updatedAt) }}
              </em>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="112">
          <template #default="{ row }">
            <el-tooltip
              :content="row.status === 'FAILED' ? row.failReason || statusMeta(row.status).hint : statusMeta(row.status).hint"
              placement="top"
            >
              <span class="kb-status" :class="`kb-status--${statusMeta(row.status).variant}`">
                {{ statusMeta(row.status).label }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="分块数" width="88" align="center">
          <template #default="{ row }">
            <span class="mono kb-chunks">{{ row.chunks }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="210" align="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              text
              type="primary"
              size="small"
              :disabled="row.status === 'INDEXING' || row.status === 'PARSING'"
              @click="rebuild(row)"
            >
              重建索引
            </el-button>
            <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="kb-empty">
            <el-icon><Collection /></el-icon>
            <p>还没有匹配的知识文章</p>
            <el-button size="small" @click="openCreate">新建第一篇</el-button>
          </div>
        </template>
      </el-table>
    </section>

    <!-- 编辑抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      :title="drawerTitle"
      size="560px"
      :before-close="closeDrawer"
      class="kb-drawer"
    >
      <div class="kb-form">
        <el-form label-position="top">
          <el-form-item label="标题" required>
            <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="例如：DS2208 扫码枪驱动安装与故障排查" />
          </el-form-item>

          <el-form-item label="分类" required>
            <el-select v-model="form.category" class="kb-form__category">
              <el-option
                v-for="item in CATEGORY_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="正文（Markdown）" required>
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="13"
              placeholder="支持 Markdown，# 标题、- 列表、1. 步骤"
              class="kb-form__content"
            />
          </el-form-item>

          <el-form-item label="附件">
            <el-upload
              drag
              :auto-upload="false"
              :show-file-list="false"
              accept=".md,.txt,.pdf,.docx"
              :on-change="handleUpload"
              class="kb-upload"
            >
              <el-icon class="kb-upload__icon"><UploadFilled /></el-icon>
              <div class="kb-upload__text">拖拽 PDF / Word / Markdown 到这里，或点击选择</div>
              <div class="kb-upload__hint">原型阶段仅演示选择动作，保存并索引时解析正文</div>
            </el-upload>
          </el-form-item>
        </el-form>

        <div v-if="!isCreating" class="kb-form__status">
          <span>当前索引状态</span>
          <span class="kb-status" :class="`kb-status--${statusMeta(articles.find((item) => item.id === form.id)?.status || 'PENDING').variant}`">
            {{ statusMeta(articles.find((item) => item.id === form.id)?.status || 'PENDING').label }}
          </span>
        </div>
      </div>

      <template #footer>
        <div class="kb-form__footer">
          <el-button @click="closeDrawer">取消</el-button>
          <el-button :loading="saving" @click="save()">保存</el-button>
          <el-button type="primary" :loading="saving" @click="save(true)">保存并索引</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.kb {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.kb__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.kb__title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.kb__desc {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--sd-text-muted);
}

/* 概览 */
.kb__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.kb-stat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 18px;
  border-radius: 12px;
  background: var(--sd-card);
  box-shadow: var(--sd-shadow);
}

.kb-stat__label {
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.kb-stat strong {
  font-size: 20px;
  font-weight: 600;
}

.kb-stat__value--green {
  color: var(--sd-green);
}

.kb-stat__value--blue {
  color: var(--sd-primary);
}

.kb-stat__value--red {
  color: var(--sd-red);
}

/* 筛选 */
.kb__filters {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.kb__search {
  width: 280px;
}

.kb__status {
  width: 140px;
}

.kb__count {
  margin-left: auto;
  font-size: 12px;
  color: var(--sd-text-muted);
}

/* 表格 */
.kb-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kb-cell__title {
  font-size: 13px;
  color: var(--sd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-cell__meta {
  font-style: normal;
  font-size: 11px;
  color: var(--sd-text-muted);
}

.kb-chunks {
  font-size: 12px;
  color: var(--sd-text-secondary);
}

/* 状态标签 */
.kb-status {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  background: #f2f5fa;
  color: #6b7a90;
}

.kb-status--pending {
  background: #f2f5fa;
  color: #6b7a90;
}

.kb-status--running {
  background: #e8f0ff;
  color: #1d4ed8;
}

.kb-status--ready {
  background: var(--sd-green-soft);
  color: var(--sd-green);
}

.kb-status--failed {
  background: var(--sd-red-soft);
  color: var(--sd-red);
}

/* 空状态 */
.kb-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 34px 0;
  color: var(--sd-text-muted);
  font-size: 22px;
}

.kb-empty p {
  margin: 0;
  font-size: 12px;
}

/* 抽屉表单 */
.kb-form {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.kb-form__category {
  width: 100%;
}

.kb-form__content :deep(textarea) {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.8;
}

.kb-upload {
  width: 100%;
}

.kb-upload :deep(.el-upload) {
  width: 100%;
}

.kb-upload :deep(.el-upload-dragger) {
  padding: 18px 12px;
  border-radius: 10px;
}

.kb-upload__icon {
  font-size: 26px;
  color: #a9b6c9;
}

.kb-upload__text {
  margin-top: 6px;
  font-size: 12px;
  color: var(--sd-text-secondary);
}

.kb-upload__hint {
  margin-top: 2px;
  font-size: 10px;
  color: var(--sd-text-muted);
}

.kb-form__status {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--sd-text-muted);
}

.kb-form__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 1100px) {
  .kb__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .kb__filters {
    flex-wrap: wrap;
  }
}
</style>
