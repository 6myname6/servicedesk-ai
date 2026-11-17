/**
 * 解析后端返回的时间字符串。
 *
 * <p>后端序列化为 {@code yyyy-MM-dd'T'HH:mm:ss}（无时区），直接用 new Date()
 * 在部分浏览器会按 UTC 处理导致偏移，这里手工拆分按本地时间构造。</p>
 *
 * @param {string|Date|null} value 时间字符串或 Date
 * @returns {Date|null} 解析结果，入参为空时返回 null
 */
export function parseDateTime(value) {
  if (!value) {
    return null
  }
  if (value instanceof Date) {
    return value
  }
  const matched = String(value).match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?/)
  if (!matched) {
    const fallback = new Date(value)
    return Number.isNaN(fallback.getTime()) ? null : fallback
  }
  return new Date(
    Number(matched[1]),
    Number(matched[2]) - 1,
    Number(matched[3]),
    Number(matched[4] || 0),
    Number(matched[5] || 0),
    Number(matched[6] || 0)
  )
}

/**
 * 补零。
 *
 * @param {number} value 数字
 * @returns {string} 两位字符串
 */
function pad(value) {
  return String(value).padStart(2, '0')
}

/**
 * 格式化为「MM-DD HH:mm」，用于列表副标题。
 *
 * @param {string|Date} value 时间
 * @returns {string} 格式化结果，入参为空时返回 '-'
 */
export function formatShortDateTime(value) {
  const date = parseDateTime(value)
  if (!date) {
    return '-'
  }
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 格式化为「yyyy-MM-dd HH:mm」，用于详情与弹窗。
 *
 * @param {string|Date} value 时间
 * @returns {string} 格式化结果，入参为空时返回 '-'
 */
export function formatDateTime(value) {
  const date = parseDateTime(value)
  if (!date) {
    return '-'
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
    date.getMinutes()
  )}`
}

/**
 * 格式化为「yyyy-MM-dd」。
 *
 * @param {string|Date} value 日期
 * @returns {string} 格式化结果，入参为空时返回 '-'
 */
export function formatDate(value) {
  const date = parseDateTime(value)
  if (!date) {
    return '-'
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * 把剩余分钟转成人类可读文本。
 *
 * @param {number|null|undefined} minutes 剩余分钟，负数表示已超时
 * @returns {string} 例如「45 分钟」「2 小时」「已超时」
 */
export function formatMinutes(minutes) {
  if (minutes === null || minutes === undefined) {
    return '-'
  }
  if (minutes <= 0) {
    return '已超时'
  }
  if (minutes < 60) {
    return `${minutes} 分钟`
  }
  if (minutes < 1440) {
    const hours = Math.floor(minutes / 60)
    return `${hours} 小时`
  }
  return `${Math.floor(minutes / 1440)} 天`
}

/**
 * 判断 SLA 剩余的紧急程度，用于着色。
 *
 * @param {number|null|undefined} minutes 剩余分钟
 * @param {boolean} breached 后端标记的是否已超时
 * @returns {'danger'|'warning'|'normal'} danger &lt; 30 分钟，warning &lt; 120 分钟
 */
export function slaLevel(minutes, breached) {
  if (breached || minutes === null || minutes === undefined || minutes <= 0) {
    return 'danger'
  }
  if (minutes < 30) {
    return 'danger'
  }
  if (minutes < 120) {
    return 'warning'
  }
  return 'normal'
}

/**
 * 格式化文件大小。
 *
 * @param {number} bytes 字节数
 * @returns {string} 例如「1.2 MB」
 */
export function formatFileSize(bytes) {
  if (!bytes && bytes !== 0) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
