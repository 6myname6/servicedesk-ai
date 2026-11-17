import http from './http'

/**
 * 新建工单。
 *
 * @param {object} payload TicketCreateRequest，见接口文档 2.2
 * @returns {Promise<object>} 建单后的工单详情（含工单号与 SLA deadline）
 */
export function createTicket(payload) {
  return http.post('/tickets', payload)
}

/**
 * 分页查询工单，数据范围由后端按当前登录身份裁剪。
 *
 * @param {object} params 关键词、状态、紧急度、分类、我相关与分页参数
 * @returns {Promise<{records: object[], total: number, curPage: number, pageSize: number, pages: number}>}
 */
export function pageTickets(params) {
  return http.get('/tickets', { params })
}
