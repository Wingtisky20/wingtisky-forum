import { computed, ref } from 'vue'

/**
 * 登录态。
 *
 * <p>**刻意不引入 Pinia**：这里只有"令牌"和"当前用户"两个字段。
 * 为它装一个状态管理库，第一个要写的往往是"把 localStorage 包一层"——
 * 收益为负。真需要跨页面的复杂状态时再引入不迟。
 *
 * <p>模块级的 `ref` 让整个应用共享同一份状态（`useAuth()` 每次返回的是同一组 ref）。
 *
 * <p><b>M2 的已知简化</b>：只存访问令牌，**不接续期**。访问令牌 30 分钟过期，
 * 过期后会被拦到登录页重新登一次。刷新令牌在后端是现成的，接上它是十几行的事，
 * 但那属于"体验优化"而不是 M2 要交付的东西——写在这里，免得看起来像漏了。
 */

const TOKEN_KEY = 'wt_access_token'
const USER_KEY = 'wt_user'

const token = ref(localStorage.getItem(TOKEN_KEY) || '')
const user = ref(readUser())

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    // 本地存的东西坏了不该让整个应用起不来
    return null
  }
}

export function useAuth() {
  const isLoggedIn = computed(() => Boolean(token.value))

  /**
   * 存令牌。**必须与 `setUser` 分开、而且先调它**——
   * 登录响应里只有令牌、没有用户资料，资料要另外请求 `GET /users/me`，
   * 而那个请求要靠请求拦截器带上令牌。合成一个方法是能省一次调用，
   * 但那会逼出一个顺序依赖（先存令牌才发得出下一个请求），
   * 而这个顺序在调用点上是看不见的。
   */
  function setToken(value) {
    token.value = value || ''
    localStorage.setItem(TOKEN_KEY, token.value)
  }

  function setUser(value) {
    user.value = value || null
    localStorage.setItem(USER_KEY, JSON.stringify(user.value))
  }

  function clear() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, isLoggedIn, setToken, setUser, clear }
}
