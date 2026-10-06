<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { authApi } from './api'
import { showError } from './api/client'
import { useAuth } from './composables/useAuth'

const router = useRouter()
const { user, isLoggedIn, clear } = useAuth()

async function onCommand(command) {
  if (command !== 'logout') {
    return
  }
  try {
    // 后端会把这个用户的刷新令牌删掉。失败也继续清本地——
    // "退出登录"这个动作不该因为网络问题而失败
    await authApi.logout()
  } catch (error) {
    showError(error)
  }
  clear()
  ElMessage.success('已退出登录')
  router.push('/')
}
</script>

<template>
  <el-container class="wt-app">
    <!-- 顶栏在**所有页面**共用：没登录时展示登录入口，登录后展示发帖入口与用户名 -->
    <el-header class="wt-header">
      <div class="wt-header__inner">
        <router-link to="/" class="wt-brand">深栈</router-link>

        <div class="wt-header__actions">
          <template v-if="isLoggedIn">
            <el-button type="primary" @click="router.push('/posts/new')">发帖</el-button>
            <el-dropdown @command="onCommand">
              <span class="wt-user">
                {{ user?.nickname || user?.username }}
                <el-icon class="wt-user__caret"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>

          <el-button v-else @click="router.push('/login')">登录 / 注册</el-button>
        </div>
      </div>
    </el-header>

    <el-main class="wt-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.wt-app {
  min-height: 100vh;
}

.wt-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  /* 顶栏占满宽度，但里面的内容与页面主体对齐——两者错开的话，
     视觉上会有一条对不齐的竖线 */
  display: flex;
  align-items: center;
  padding: 0;
}

.wt-header__inner {
  width: 100%;
  max-width: var(--wt-content-width);
  margin: 0 auto;
  padding: 0 var(--wt-gap);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.wt-brand {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  text-decoration: none;
}

.wt-header__actions {
  display: flex;
  align-items: center;
  gap: var(--wt-gap);
}

.wt-user {
  cursor: pointer;
  color: #303133;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.wt-user__caret {
  font-size: 12px;
  color: #909399;
}

.wt-main {
  padding: 0;
}
</style>
