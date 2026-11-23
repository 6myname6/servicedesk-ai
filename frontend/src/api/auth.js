import http from './http'

/**
 * 用户名密码登录。
 *
 * @param {{username: string, password: string}} payload 登录入参
 * @returns {Promise<{token: string, expiresIn: number, user: object}>} token 与用户信息
 */
export function login(payload) {
  return http.post('/auth/login', payload, { skipAuthRedirect: true, silent: true })
}

/**
 * 退出登录，把当前 token 加入黑名单。
 *
 * @returns {Promise<void>}
 */
export function logout() {
  return http.post('/auth/logout', null, { silent: true })
}

/**
 * 查询当前登录用户。
 *
 * @returns {Promise<object>} 用户信息
 */
export function fetchCurrentUser() {
  return http.get('/auth/me', { silent: true })
}

/**
 * 修改当前登录用户的密码。
 *
 * @param {{oldPassword: string, newPassword: string}} payload 原密码与新密码
 * @returns {Promise<void>}
 */
export function changePassword(payload) {
  return http.put('/auth/password', payload)
}
