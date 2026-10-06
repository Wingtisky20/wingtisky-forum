<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi, userApi } from '../api'
import { showError } from '../api/client'
import { useAuth } from '../composables/useAuth'

const route = useRoute()
const router = useRouter()
const { setToken, setUser } = useAuth()

/** 登录与注册共用一个页面：两者字段高度重合，来回跳页反而更麻烦。 */
const mode = ref('login')
const submitting = ref(false)
const formRef = ref()

const form = reactive({ username: '', password: '', nickname: '' })

/*
 * 前端校验规则**与后端保持一致**（用户名 3~32 且只含字母数字下划线连字符、
 * 密码 8~64）。
 *
 * ⚠️ 但它**只是体验，不是安全**：真正说了算的永远是后端——
 * 前端能拦住的只有"用正常界面提交"这一种情况，手改请求、直接调接口都绕得过去。
 * 之所以要把两边的规则写成一样，是为了**别让用户被两套说法搞糊涂**：
 * 前端说可以、后端说不行的话，用户会以为是 bug。
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度需在 3~32 之间', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]+$/,
      message: '用户名只能包含字母、数字、下划线、连字符',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需在 8~64 之间', trigger: 'blur' }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 32, message: '昵称最长 32 个字符', trigger: 'blur' }
  ]
}

function switchMode(next) {
  mode.value = next
  formRef.value?.clearValidate()
}

/**
 * 一键填入演示账号。
 *
 * <p>**这个账号的密码是故意公开的**——它存在的意义就是让看这个项目的人
 * 不必注册就能进来点一遍。所以密码明文写在代码里是**有意的**，不是疏忽。
 * 它只有普通用户权限，也不会被用于任何真实用途。
 */
function fillDemo() {
  mode.value = 'login'
  form.username = 'demo'
  form.password = 'demo12345'
  formRef.value?.clearValidate()
}

/** 校验通过返回 true；不通过返回 false（而不是抛异常，免得每个调用点都要 try）。 */
async function validate() {
  try {
    await formRef.value.validate()
    return true
  } catch {
    return false
  }
}

/**
 * 登录成功后把会话装好。
 *
 * <p>**顺序不能颠倒**：先存令牌，再请求当前用户资料——
 * 那个请求要靠请求拦截器从本地取令牌带上。反过来的话会拿到 401。
 */
async function establishSession(loginResult) {
  setToken(loginResult.accessToken)
  const profile = await userApi.me()
  setUser(profile)
}

async function onSubmit() {
  if (!(await validate())) {
    return
  }

  submitting.value = true
  try {
    if (mode.value === 'register') {
      await authApi.register({
        username: form.username,
        password: form.password,
        nickname: form.nickname
      })
      ElMessage.success('注册成功，正在登录')
    }

    const result = await authApi.login({ username: form.username, password: form.password })
    await establishSession(result)

    ElMessage.success('登录成功')
    // 登录前想去哪，登录后回哪；直接进来的就回首页
    router.replace(route.query.redirect || '/')
  } catch (error) {
    showError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="wt-container wt-login">
    <el-card shadow="never">
      <template #header>
        <div class="wt-login__head">
          <span class="wt-login__title">{{ mode === 'login' ? '登录' : '注册' }}</span>
          <el-link type="primary" :underline="false" @click="switchMode(mode === 'login' ? 'register' : 'login')">
            {{ mode === 'login' ? '还没有账号？去注册' : '已有账号？去登录' }}
          </el-link>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px" @submit.prevent="onSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3~32 位，字母数字下划线连字符" />
        </el-form-item>

        <el-form-item v-if="mode === 'register'" label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="别人看到的名字" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" native-type="submit">
            {{ mode === 'login' ? '登录' : '注册并登录' }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 演示账号提示。
         放在**登录页**而不是只写在 README 里：访客走到这一步才需要它，
         让他为了找一个账号去翻文档，等于把他挡在门外。 -->
    <el-alert type="info" :closable="false" class="wt-demo">
      <template #title>
        演示账号：<b>demo</b> / <b>demo12345</b>
      </template>
      <div class="wt-demo__hint">
        普通用户权限，可直接使用——发帖、评论、点赞、收藏、按标签筛选。
        <el-link type="primary" :underline="false" @click="fillDemo">一键填入</el-link>
      </div>
    </el-alert>
  </div>
</template>

<style scoped>
.wt-login {
  /* 登录页只有一张卡，别让它铺满整个屏幕宽度 */
  max-width: 460px;
  padding-top: 64px;
}

.wt-login__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.wt-login__title {
  font-size: 16px;
  font-weight: 600;
}

.wt-demo {
  margin-top: var(--wt-gap);
}

.wt-demo__hint {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
}
</style>
