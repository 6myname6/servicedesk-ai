import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/layouts/AppLayout.vue'

/**
 * 路由表，对应产品文档 6.1 页面清单。
 *
 * <p>{@code meta.roles} 为空表示所有登录用户可见；有值时按角色拦截，
 * 具体数据范围仍由后端裁剪，前端只做入口级控制。</p>
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: AppLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '工作台' }
      },
      {
        path: 'tickets',
        name: 'tickets',
        component: () => import('@/views/tickets/TicketListView.vue'),
        meta: { title: '工单管理' }
      },
      {
        path: 'tickets/new',
        name: 'ticket-create',
        component: () => import('@/views/tickets/TicketCreateView.vue'),
        meta: { title: '新建工单', parent: { title: '工单管理', path: '/tickets' } }
      },
      {
        path: 'tickets/:id',
        name: 'ticket-detail',
        component: () => import('@/views/tickets/TicketDetailView.vue'),
        meta: { title: '工单详情', parent: { title: '工单管理', path: '/tickets' } }
      },
      {
        path: 'kb',
        name: 'kb',
        component: () => import('@/views/KbView.vue'),
        meta: { title: '知识库', roles: ['SUPERVISOR', 'ADMIN'] }
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/ReportsView.vue'),
        meta: { title: '运营报表', roles: ['SUPERVISOR', 'ADMIN'] }
      },
      {
        path: 'admin/assets',
        name: 'admin-assets',
        component: () => import('@/views/admin/AssetsView.vue'),
        meta: { title: '客户与设备', roles: ['SUPERVISOR', 'ADMIN'] }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/SystemView.vue'),
        meta: { title: '用户管理', roles: ['ADMIN'] }
      },
      {
        path: 'admin/teams',
        name: 'admin-teams',
        component: () => import('@/views/admin/SystemView.vue'),
        meta: { title: '团队管理', roles: ['ADMIN'] }
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/ForbiddenView.vue'),
        meta: { title: '无访问权限' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    return auth.isLoggedIn && to.path === '/login' ? { path: '/dashboard' } : true
  }

  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 有 token 但没有用户信息（如缓存被清）：先拉一次 /auth/me 校验并补齐
  if (!auth.user) {
    try {
      await auth.loadMe()
    } catch {
      auth.clear()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const roles = to.meta.roles
  if (Array.isArray(roles) && roles.length > 0 && !roles.includes(auth.role)) {
    return { path: '/403' }
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 智维工单` : '智维工单 · ServiceDesk AI'
})

export default router
