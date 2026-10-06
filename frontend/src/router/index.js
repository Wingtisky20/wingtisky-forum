import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../composables/useAuth'

const routes = [
  {
    path: '/',
    name: 'post-list',
    component: () => import('../views/PostListView.vue')
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue')
  },
  {
    // ⚠️ 这条要写在 `/posts/:id` 前面：虽然 vue-router 4 会优先匹配静态段，
    // 但按"具体在前"的顺序写，读的人不必去回忆那条规则
    path: '/posts/new',
    name: 'post-create',
    component: () => import('../views/PostCreateView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/posts/:id',
    name: 'post-detail',
    component: () => import('../views/PostDetailView.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 路由切换时回到顶部：从列表翻到详情还停在页面中间的话，
  // 会以为新页面没加载出来
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 需要登录的页面在路由层拦一道。
 *
 * <p>**这只是体验，不是安全**——真正拦住越权的是后端的鉴权
 * （`@PreAuthorize` 与资源归属校验）。前端拦不住的场景很多：
 * 用户手改 localStorage、直接调接口、令牌过期而本地还没清。
 * 这里的价值是"别让用户填完一整篇帖子才告诉他没登录"。
 */
router.beforeEach((to) => {
  const { isLoggedIn } = useAuth()
  if (to.meta.requiresAuth && !isLoggedIn.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
