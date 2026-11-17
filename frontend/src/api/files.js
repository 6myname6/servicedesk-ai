import http from './http'

/**
 * 上传附件到服务端（当前实现为阿里云 OSS）。
 *
 * @param {File} file 文件对象
 * @param {string} [bizType='TICKET'] 业务类型 TICKET / KB
 * @returns {Promise<{id: number, fileName: string, fileUrl: string, fileSize: number, mimeType: string}>} 附件信息
 */
export function uploadFile(file, bizType = 'TICKET') {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', bizType)
  return http.post('/files', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000
  })
}

/**
 * 删除附件。
 *
 * @param {number} id 附件 ID
 * @returns {Promise<void>}
 */
export function deleteFile(id) {
  return http.delete(`/files/${id}`)
}
