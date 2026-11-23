<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'

import AgentDrawer from '@/components/AgentDrawer.vue'
import { useAuthStore } from '@/stores/auth'

/** 左侧导航项，roles 为空表示所有角色可见。 */
const MENUS = [
  { label: '工作台', path: '/dashboard', icon: 'Odometer', match: ['/dashboard'] },
  { label: '工单管理', path: '/tickets', icon: 'Tickets', match: ['/tickets'] },
  {
    label: '知识库',
    path: '/kb',
    icon: 'Collection',
    match: ['/kb'],
    roles: ['SUPERVISOR', 'ADMIN']
  },
  {
    label: '运营报表',
    path: '/reports',
    icon: 'TrendCharts',
    match: ['/reports'],
    roles: ['SUPERVISOR', 'ADMIN']
  },
  {
    label: '客户与设备',
    path: '/admin/assets',
    icon: 'Monitor',
    match: ['/admin/assets'],
    roles: ['SUPERVISOR', 'ADMIN']
  },
  {
    label: 'SLA 策略',
    path: '/admin/sla',
    icon: 'Timer',
    match: ['/admin/sla'],
    roles: ['ADMIN']
  },
  {
    label: '系统管理',
    path: '/admin/users',
    icon: 'Setting',
    match: ['/admin/users', '/admin/teams'],
    roles: ['ADMIN']
  }
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 当前角色可见的菜单。 */
const visibleMenus = computed(() =>
  MENUS.filter((item) => !item.roles || item.roles.includes(auth.role))
)

/** 当前页面标题，用于面包屑末级。 */
const currentTitle = computed(() => route.meta.title || '')

/** 面包屑上级，例如「工单管理 / 新建工单」。 */
const parentCrumb = computed(() => route.meta.parent || null)

/** 顶栏日期，格式 yyyy-MM-dd。 */
const todayText = computed(() => {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
})

/** 头像上的姓名首字。 */
const avatarText = computed(() => (auth.user?.displayName || '用').slice(0, 1))

/** 侧栏副标题：角色 + 归属，团队与客户互斥，最多显示一个。 */
const orgLabel = computed(() => {
  const orgName = auth.user?.teamName || auth.user?.customerName
  return orgName ? `${auth.roleLabel} · ${orgName}` : auth.roleLabel
})

/** 当前页不是建单页时才显示顶栏的建单入口。 */
const showCreateButton = computed(() => route.name !== 'ticket-create')

/**
 * 判断导航项是否处于选中态。
 *
 * @param {object} item 菜单项
 * @returns {boolean} 命中当前路径前缀即为选中
 */
function isActive(item) {
  return item.match.some((prefix) => route.path === prefix || route.path.startsWith(`${prefix}/`))
}

/** 跳转新建工单。 */
function goCreate() {
  router.push('/tickets/new')
}

/**
 * 顶部头像下拉：个人中心跳转，其余命令（退出登录）走原逻辑。
 *
 * @param {string} command 下拉项命令
 */
function handleUserCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
    return
  }
  handleLogout()
}

/** 退出登录，二次确认后回到登录页。 */
async function handleLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录，确定退出吗？', '退出登录', {
      confirmButtonText: '退出登录',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="sd-layout">
    <aside class="sd-sidebar">
      <div class="sd-brand">
        <span class="sd-brand__logo">
          <el-icon><Service /></el-icon>
        </span>
        <span class="sd-brand__text">
          <strong>智维工单</strong>
          <em>ServiceDesk AI</em>
        </span>
      </div>

      <nav class="sd-nav">
        <router-link
          v-for="item in visibleMenus"
          :key="item.path"
          :to="item.path"
          class="sd-nav__item"
          :class="{ 'is-active': isActive(item) }"
        >
          <el-icon class="sd-nav__icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <router-link to="/profile" class="sd-sidebar__footer sd-sidebar__footer--link">
        <span class="sd-avatar sd-avatar--sm">{{ avatarText }}</span>
        <span class="sd-sidebar__user">
          <strong>{{ auth.user?.displayName || '未登录' }}</strong>
          <em>{{ orgLabel }}</em>
        </span>
      </router-link>
    </aside>

    <div class="sd-main">
      <header class="sd-topbar">
        <div class="sd-crumb">
          <span class="sd-crumb__root">服务台</span>
          <span class="sd-crumb__sep">/</span>
          <router-link v-if="parentCrumb" :to="parentCrumb.path" class="sd-crumb__link">
            {{ parentCrumb.title }}
          </router-link>
          <span v-if="parentCrumb" class="sd-crumb__sep">/</span>
          <strong class="sd-crumb__current">{{ currentTitle }}</strong>
        </div>

        <div class="sd-topbar__right">
          <el-button v-if="showCreateButton" type="primary" class="sd-create-btn" @click="goCreate">
            <el-icon><Plus /></el-icon>
            <span>新建工单</span>
          </el-button>
          <span class="sd-topbar__date mono">{{ todayText }}</span>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="sd-user-entry">
              <span class="sd-avatar">{{ avatarText }}</span>
              <el-icon class="sd-user-entry__caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  {{ auth.user?.displayName }} · {{ auth.roleLabel }}
                </el-dropdown-item>
                <el-dropdown-item command="profile" divided>个人中心</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="sd-content">
        <router-view />
      </main>
    </div>

    <!-- 全局 AI 坐席入口（C-01） -->
    <AgentDrawer />
  </div>
</template>

<style scoped>
.sd-layout {
  display: flex;
  min-height: 100vh;
  background: var(--sd-bg);
}

/* 侧边栏 */
.sd-sidebar {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  width: var(--sd-sidebar-width);
  display: flex;
  flex-direction: column;
  background: var(--sd-navy);
  color: #cbd7e4;
  z-index: 20;
}

.sd-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.sd-brand__logo {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #19c6b4, #0e9c8e);
  color: #fff;
  font-size: 18px;
}

.sd-brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.sd-brand__text strong {
  color: #fff;
  font-size: 15px;
  letter-spacing: 1px;
}

.sd-brand__text em {
  font-style: normal;
  font-size: 10px;
  color: #86a0b8;
  letter-spacing: 0.4px;
}

.sd-nav {
  flex: 1;
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}

.sd-nav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0 14px;
  border-radius: 8px;
  color: #c2d0de;
  font-size: 13px;
  transition: background 0.16s ease, color 0.16s ease;
}

.sd-nav__item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.sd-nav__item.is-active {
  background: var(--sd-navy-active);
  color: #fff;
}

.sd-nav__item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 2px;
  background: var(--sd-teal);
}

.sd-nav__icon {
  font-size: 15px;
  opacity: 0.9;
}

.sd-sidebar__footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

/* 左下角账号区整块可点击，进入个人中心 */
.sd-sidebar__footer--link {
  color: inherit;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.2s ease;
}

.sd-sidebar__footer--link:hover {
  background: rgba(255, 255, 255, 0.06);
}

.sd-sidebar__user {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
  overflow: hidden;
}

.sd-sidebar__user strong {
  color: #fff;
  font-size: 13px;
  font-weight: 500;
}

.sd-sidebar__user em {
  font-style: normal;
  font-size: 10px;
  color: #8ba3b9;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 顶部栏 */
.sd-main {
  flex: 1;
  min-width: 0;
  margin-left: var(--sd-sidebar-width);
  display: flex;
  flex-direction: column;
}

.sd-topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  height: var(--sd-topbar-height);
  padding: 0 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid var(--sd-line);
}

.sd-crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.sd-crumb__root {
  color: var(--sd-text-muted);
}

.sd-crumb__sep {
  color: #c4cddb;
}

.sd-crumb__link {
  color: var(--sd-text-secondary);
}

.sd-crumb__link:hover {
  color: var(--sd-primary);
}

.sd-crumb__current {
  color: var(--sd-text);
  font-weight: 600;
  font-size: 14px;
}

.sd-topbar__right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.sd-create-btn {
  height: 34px;
  padding: 0 16px;
  font-size: 13px;
}

.sd-topbar__date {
  font-size: 12px;
  color: var(--sd-text-muted);
}

.sd-user-entry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.sd-user-entry__caret {
  font-size: 12px;
  color: var(--sd-text-muted);
}

/* 头像 */
.sd-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #19c6b4, #0f8f86);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.sd-avatar--sm {
  width: 30px;
  height: 30px;
  font-size: 12px;
}

/* 内容区 */
.sd-content {
  flex: 1;
  padding: 22px 32px 32px;
  min-width: 0;
}
</style>
