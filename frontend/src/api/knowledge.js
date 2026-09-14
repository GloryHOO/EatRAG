/**
 * 知识库相关 REST 接口封装（原生 fetch）
 *
 * 后端接口按约定统一返回 { code, message, data }，code !== 200 时抛错，
 * 各函数返回解析后的 data 字段。
 */

/**
 * 发起请求并统一处理响应
 * @param {string} url 请求地址
 * @param {object} options fetch 选项
 * @returns {Promise<any>} 解析后的 data 字段
 */
async function request(url, options = {}) {
  const res = await fetch(url, options)
  const body = await res.json()
  // 后端约定 code !== 200 视为业务错误
  if (!res.ok || body.code !== 200) {
    throw new Error(body.message || '请求失败')
  }
  return body.data
}

/**
 * 分页查询文档列表
 * @param {object} params 查询参数
 * @param {string} [params.category]   分类
 * @param {string} [params.difficulty] 难度
 * @param {string} [params.keyword]    关键词
 * @param {number} [params.page]       页码
 * @param {number} [params.size]       每页条数
 * @returns {Promise<any>} 文档列表数据
 */
export function fetchDocuments({ category, difficulty, keyword, page, size } = {}) {
  const query = new URLSearchParams()
  if (category) query.append('category', category)
  if (difficulty) query.append('difficulty', difficulty)
  if (keyword) query.append('keyword', keyword)
  if (page != null) query.append('page', String(page))
  if (size != null) query.append('size', String(size))

  const qs = query.toString()
  const url = '/api/knowledge/documents' + (qs ? '?' + qs : '')
  return request(url)
}

/**
 * 查询文档详情
 * @param {string} parentId 父文档 ID
 * @returns {Promise<any>} 文档详情数据
 */
export function fetchDocumentDetail(parentId) {
  return request('/api/knowledge/documents/' + encodeURIComponent(parentId))
}

/**
 * 查询知识库统计信息
 * @returns {Promise<any>} 统计信息数据
 */
export function fetchStats() {
  return request('/api/knowledge/stats')
}

/**
 * 全量重建索引
 * @returns {Promise<any>} 重建结果数据
 */
export function rebuildIndex() {
  return request('/api/knowledge/rebuild', { method: 'POST' })
}