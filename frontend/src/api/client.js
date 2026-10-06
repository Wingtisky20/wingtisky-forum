import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuth } from '../composables/useAuth'

/**
 * 与后端通信的唯一入口。
 *
 * <p>`baseURL` 写 `/api` 而不是后端的完整地址：开发时由 Vite 代理转发
 * （见 `vite.config.js`），**前后端在浏览器看来是同源的**。
 * 写死 `http://localhost:8080` 的话，上线部署时这里要改一次，
 * 而"忘了改"的表现是请求全部 404。
 */
const client = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// 请求拦截器：自动带上令牌。
// 放在这里而不是每个调用点写一遍——漏写一处的表现是"这个页面的数据加载不出来"，
// 而它看起来像后端的问题。
client.interceptors.request.use((config) => {
  const { token } = useAuth()
  if (token.value) {
    config.headers.Authorization = `Bearer ${token.value}`
  }
  return config
})

/** 把后端的统一响应体转成一个带错误码的 Error，供上层判断。 */
function toError(body) {
  const error = new Error(body?.message || '请求失败')
  error.code = body?.code
  error.traceId = body?.traceId
  return error
}

/**
 * 令牌失效时清掉会话并回登录页。
 *
 * <p><b>⚠️ 这里按「错误码」判断，不按 HTTP 状态码。</b>
 * 401 在本项目里有两种含义：
 * <ul>
 *   <li>`A0002` 未登录 / 令牌过期 → 该去登录页</li>
 *   <li>`A0102` 用户名或密码错误 → **该留在登录页把错误显示出来**</li>
 * </ul>
 * 只看 401 就跳转的话，用户在登录页输错密码会被"刷新一下"，
 * 刚看到的错误提示当场消失——像是页面坏了。
 *
 * <p>用 `window.location` 而不是 router：client 是被 router 间接依赖的，
 * 反过来 import router 会形成循环依赖。这里也不需要"无刷新跳转"，
 * 令牌失效本来就该重新来一遍。
 */
function redirectIfTokenInvalid(body) {
  if (body?.code !== 'A0002') {
    return
  }
  useAuth().clear()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

// 响应拦截器：**把统一响应体外层剥掉**，让调用方直接拿到 data。
// 不剥的话，每个页面都要写一遍 `res.data.data`——多一层是小事，
// 但总有人会写成 `res.data`，而那时的表现是"数据是 undefined"。
client.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.code === '0') {
      return body.data
    }
    return Promise.reject(toError(body))
  },
  (error) => {
    const body = error.response?.data
    if (body && body.code) {
      redirectIfTokenInvalid(body)
      return Promise.reject(toError(body))
    }
    // 连响应体都没有：网络断了，或者后端没起来
    ElMessage.error('网络异常，请确认后端已启动')
    return Promise.reject(error)
  }
)

/**
 * 统一的错误提示。
 *
 * <p>各页面调它，而不是各自 `ElMessage.error`——这样"要不要提示、
 * 提示什么"只有一处逻辑，也避免了"同一个错误弹两次"。
 */
export function showError(error) {
  // 令牌失效那条已经在拦截器里处理了（跳登录页），这里再弹一次是噪音
  if (error?.code === 'A0002') {
    return
  }
  ElMessage.error(error?.message || '操作失败')
}

export default client
