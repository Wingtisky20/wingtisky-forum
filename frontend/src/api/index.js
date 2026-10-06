import client from './client'

/**
 * 后端接口的调用清单。
 *
 * <p>集中在这里而不是散在各个页面里：接口路径一变，改一处；
 * 也让"前端到底用了后端哪些能力"一眼能看全。
 *
 * <p>返回的都是**已经剥掉统一响应体外层**的 `data`（拦截器做的），
 * 所以调用方直接拿到业务数据，不写 `res.data.data`。
 */

// ---------- 认证 ----------

export const authApi = {
  register: (payload) => client.post('/auth/register', payload),
  login: (payload) => client.post('/auth/login', payload),
  logout: () => client.post('/auth/logout')
}

// ---------- 用户 ----------

export const userApi = {
  me: () => client.get('/users/me')
}

// ---------- 帖子 ----------

export const postApi = {
  /** @param params `{ page, size, sort, tagId }`，字段都可省 */
  page: (params) => client.get('/posts', { params }),
  detail: (id) => client.get(`/posts/${id}`),
  create: (payload) => client.post('/posts', payload),
  /** 按作者筛走的是子资源路径（见后端设计稿 §4） */
  pageByAuthor: (authorId, params) => client.get(`/users/${authorId}/posts`, { params }),

  like: (id) => client.put(`/posts/${id}/like`),
  unlike: (id) => client.delete(`/posts/${id}/like`),
  collect: (id) => client.put(`/posts/${id}/collect`),
  uncollect: (id) => client.delete(`/posts/${id}/collect`)
}

// ---------- 评论 ----------

export const commentApi = {
  page: (postId, params) => client.get(`/posts/${postId}/comments`, { params }),
  /** @param payload `{ parentId, content }`；`parentId` 不传就是顶层评论 */
  create: (postId, payload) => client.post(`/posts/${postId}/comments`, payload)
}

// ---------- 标签 ----------

export const tagApi = {
  list: (limit = 20) => client.get('/tags', { params: { limit } })
}
