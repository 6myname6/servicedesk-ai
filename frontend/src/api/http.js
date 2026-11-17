import axios from 'axios'
import { ElMessage } from 'element-plus'

/** 本地存储中 JWT 的键名，与登录页、鉴权 store 共用。 */
export const TOKEN_KEY = 'sd_token'

/**
 * 构造带业务状态码的错误对象。
 *
 * @param {number} code 业务/HTTP 状态码
 * @param {string} message 可直接展示的中文提示
 * @returns {Error} 附带 code 字段的错误
 */
function buildError(code, message) {
  const error = new Error(message)
  error.code = code
  return error
}

/**
 * 按 HTTP 状态码给兜底提示。
 *
 * @param {number} status HTTP 状态码
 * @returns {string} 中文提示
 */
function fallbackMessage(status) {
  const map = {
    400: '请求参数不正确',
    401: '登录已失效，请重新登录',
    403: '没有访问权限',
    404: '资源不存在或无权访问',
    409: '数据已变化，请刷新后重试',
    413: '文件大小超出限制',
    429: '操作过于频繁，请稍后再试',
    500: '服务端错误，请稍后再试',
    503: '依赖服务不可用，请稍后再试'
  }
  return map[status] || '网络异常，请检查服务是否可用'
}

const http = axios.create({
  baseURL: '/api',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 后端统一响应体 {code, message, data}，非 200 视为业务失败
    if (body && typeof body.code === 'number' && body.code !== 200) {
      return Promise.reject(buildError(body.code, body.message || '请求失败'))
    }
    return body ? body.data ?? null : null
  },
  (error) => {
    const config = error.config || {}
    const status = error.response?.status
    const message = error.response?.data?.message || fallbackMessage(status)

    if (status === 401 && !config.skipAuthRedirect) {
      // 交给根组件统一处理跳转，HTTP 层不依赖 router / store
      window.dispatchEvent(new CustomEvent('sd:unauthorized'))
    }
    if (!config.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(buildError(status || -1, message))
  }
)

export default http
