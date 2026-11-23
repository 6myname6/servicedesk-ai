import http from './http'

/**
 * SLA 策略接口，对应接口文档 5.1 - 5.4（S-01）。
 *
 * <p>查询登录即可（建单页预览也用它），新增 / 修改 / 删除仅管理员；
 * 权限由后端拦截，前端只按角色隐藏入口。</p>
 */
export const slaApi = {
  /**
   * 查询全部策略，后端按优先级升序返回。
   *
   * @returns {Promise<object[]>} 策略列表，无数据时为空数组
   */
  list() {
    return http.get('/sla-policies')
  },

  /**
   * 新增策略。
   *
   * @param {{name: string, priority: string, responseMinutes: number, resolveMinutes: number, enabled?: boolean}} payload 新增入参
   * @returns {Promise<object>} 新建策略
   */
  create(payload) {
    return http.post('/sla-policies', payload)
  },

  /**
   * 修改策略，语义为整体覆盖，不含优先级。
   *
   * @param {number} id 策略 ID
   * @param {{name: string, responseMinutes: number, resolveMinutes: number, enabled: boolean}} payload 修改入参
   * @returns {Promise<object>} 修改后的策略
   */
  update(id, payload) {
    return http.put(`/sla-policies/${id}`, payload)
  },

  /**
   * 删除策略：无工单引用时物理删除，有引用时后端降级为停用。
   *
   * @param {number} id 策略 ID
   * @returns {Promise<null>} 无返回数据
   */
  remove(id) {
    return http.delete(`/sla-policies/${id}`)
  }
}
