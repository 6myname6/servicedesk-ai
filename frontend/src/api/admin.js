import http from './http'

/**
 * 客户档案接口，对应接口文档 9.8 - 9.10，仅主管与管理员可访问。
 */
export const customerApi = {
  /**
   * 分页查询客户。
   *
   * @param {{keyword?: string, page?: number, size?: number}} params 查询条件
   * @returns {Promise<object>} 分页结果
   */
  page(params) {
    return http.get('/admin/customers', { params })
  },

  /**
   * 新增客户。
   *
   * @param {object} payload CustomerCreateRequest
   * @returns {Promise<object>} 新建客户
   */
  create(payload) {
    return http.post('/admin/customers', payload)
  },

  /**
   * 修改客户，语义为整体覆盖。
   *
   * @param {number} id 客户 ID
   * @param {object} payload CustomerUpdateRequest
   * @returns {Promise<object>} 修改后的客户
   */
  update(id, payload) {
    return http.put(`/admin/customers/${id}`, payload)
  }
}

/**
 * 设备档案接口，对应接口文档 9.11 - 9.13，仅主管与管理员可访问。
 */
export const deviceApi = {
  /**
   * 分页查询设备。
   *
   * @param {{keyword?: string, customerId?: number, page?: number, size?: number}} params 查询条件
   * @returns {Promise<object>} 分页结果
   */
  page(params) {
    return http.get('/admin/devices', { params })
  },

  /**
   * 新增设备。
   *
   * @param {object} payload DeviceCreateRequest
   * @returns {Promise<object>} 新建设备
   */
  create(payload) {
    return http.post('/admin/devices', payload)
  },

  /**
   * 修改设备。
   *
   * @param {number} id 设备 ID
   * @param {object} payload DeviceUpdateRequest
   * @returns {Promise<object>} 修改后的设备
   */
  update(id, payload) {
    return http.put(`/admin/devices/${id}`, payload)
  }
}

/**
 * 用户管理接口，对应接口文档第 9 章，仅管理员可访问。
 */
export const userApi = {
  /**
   * 分页查询用户。
   *
   * @param {{keyword?: string, role?: string, status?: number, page?: number, size?: number}} params 查询条件
   * @returns {Promise<object>} 分页结果
   */
  page(params) {
    return http.get('/admin/users', { params })
  },

  /**
   * 新增用户。
   *
   * @param {object} payload UserCreateRequest
   * @returns {Promise<object>} 新建用户
   */
  create(payload) {
    return http.post('/admin/users', payload)
  },

  /**
   * 修改用户资料。
   *
   * @param {number} id 用户 ID
   * @param {object} payload UserUpdateRequest
   * @returns {Promise<object>} 修改后的用户
   */
  update(id, payload) {
    return http.put(`/admin/users/${id}`, payload)
  },

  /**
   * 启用 / 停用用户。
   *
   * @param {number} id 用户 ID
   * @param {number} status 1 启用 / 0 停用
   * @returns {Promise<void>}
   */
  updateStatus(id, status) {
    return http.patch(`/admin/users/${id}/status`, { status })
  }
}

/**
 * 团队管理接口，对应接口文档 9.5 - 9.7，仅管理员可访问。
 */
export const teamApi = {
  /**
   * 查询全部团队（含主管姓名与成员数）。
   *
   * @returns {Promise<object[]>} 团队列表
   */
  list() {
    return http.get('/admin/teams')
  },

  /**
   * 新建团队。
   *
   * @param {object} payload TeamCreateRequest
   * @returns {Promise<object>} 新建团队
   */
  create(payload) {
    return http.post('/admin/teams', payload)
  },

  /**
   * 修改团队。
   *
   * @param {number} id 团队 ID
   * @param {object} payload TeamUpdateRequest
   * @returns {Promise<object>} 修改后的团队
   */
  update(id, payload) {
    return http.put(`/admin/teams/${id}`, payload)
  }
}
