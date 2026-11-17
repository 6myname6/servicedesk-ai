import { defineStore } from 'pinia'

import { login as loginApi, logout as logoutApi, fetchCurrentUser } from '@/api/auth'
import { TOKEN_KEY } from '@/api/http'
import { ROLE_LABELS } from '@/utils/dict'

/** 本地缓存用户信息的键名，用于刷新页面时先渲染界面再拉最新数据。 */
const USER_KEY = 'sd_user'

/**
 * 从 localStorage 读取缓存的用户信息。
 *
 * @returns {object|null} 用户信息，缓存损坏时返回 null
 */
function readCachedUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

/**
 * 登录态 store：token、当前用户与角色判定。
 *
 * <p>token 只存 localStorage，不写 cookie；用户信息同时缓存一份以避免刷新时白屏，
 * 真正可信的身份仍由后端按 token 解析。</p>
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: readCachedUser()
  }),

  getters: {
    /** 是否已持有 token。 */
    isLoggedIn: (state) => Boolean(state.token),
    /** 当前角色编码，未登录时为空串。 */
    role: (state) => state.user?.role || '',
    /** 当前角色中文名。 */
    roleLabel: (state) => ROLE_LABELS[state.user?.role] || '',
    /** 是否为管理员。 */
    isAdmin: (state) => state.user?.role === 'ADMIN',
    /** 是否可维护客户 / 设备档案（主管或管理员）。 */
    canManageAssets: (state) => ['SUPERVISOR', 'ADMIN'].includes(state.user?.role)
  },

  actions: {
    /**
     * 登录并写入登录态。
     *
     * @param {{username: string, password: string}} payload 登录入参
     * @returns {Promise<object>} 登录用户信息
     */
    async login(payload) {
      const data = await loginApi(payload)
      this.token = data.token
      this.user = data.user
      localStorage.setItem(TOKEN_KEY, data.token)
      localStorage.setItem(USER_KEY, JSON.stringify(data.user))
      return data.user
    },

    /**
     * 拉取当前登录用户，用于刷新页面后校正缓存。
     *
     * @returns {Promise<object>} 用户信息
     */
    async loadMe() {
      const user = await fetchCurrentUser()
      this.user = user
      localStorage.setItem(USER_KEY, JSON.stringify(user))
      return user
    },

    /**
     * 退出登录：先通知后端拉黑 token，再清本地登录态。
     *
     * @returns {Promise<void>}
     */
    async logout() {
      try {
        await logoutApi()
      } finally {
        this.clear()
      }
    },

    /** 清空本地登录态（401 或手动退出时调用）。 */
    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
