<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { commentApi, postApi } from '../api'
import { showError } from '../api/client'
import { useAuth } from '../composables/useAuth'
import { formatCount, formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()
const { isLoggedIn } = useAuth()

const postId = Number(route.params.id)

const loading = ref(false)
const post = ref(null)
/** 帖子不存在、已删、或者被下架而你没有资格看——后端对这三种都回 404。 */
const notFound = ref(false)

const comments = ref([])
const commentTotal = ref(0)
const commentLoading = ref(false)
const commentPage = ref(1)
const commentSize = 10

const commentText = ref('')
/** 正在回复哪一条评论；`null` 表示发一条顶层评论。 */
const replyTo = ref(null)
const submitting = ref(false)
const interacting = ref(false)

async function loadPost() {
  loading.value = true
  notFound.value = false
  try {
    post.value = await postApi.detail(postId)
  } catch (error) {
    // 404 要单独处理：它不是"出错"，而是"这条帖子对你不可见"，
    // 该给一个明确的空页面，而不是弹一个红色错误框
    if (error.code === 'A0201') {
      notFound.value = true
    } else {
      showError(error)
    }
  } finally {
    loading.value = false
  }
}

async function loadComments() {
  commentLoading.value = true
  try {
    const result = await commentApi.page(postId, { page: commentPage.value, size: commentSize })
    comments.value = result.items
    commentTotal.value = result.total
  } catch (error) {
    showError(error)
    comments.value = []
    commentTotal.value = 0
  } finally {
    commentLoading.value = false
  }
}

/** 未登录时提示并带去登录页；登录后能回到这里。 */
function requireLogin(action) {
  if (isLoggedIn.value) {
    return true
  }
  ElMessage.info(`登录后才能${action}`)
  router.push({ name: 'login', query: { redirect: route.fullPath } })
  return false
}

/*
 * 点赞与收藏：**先请求成功再改本地状态**。
 *
 * 反过来（先改界面、失败再改回来）看着更"跟手"，但失败时用户会先看到
 * 数字跳了一下又跳回去——比起"点完等一下"，那个体验更让人困惑。
 */
async function toggleLike() {
  if (!requireLogin('点赞')) return
  interacting.value = true
  try {
    if (post.value.liked) {
      await postApi.unlike(postId)
      post.value.liked = false
      post.value.likeCount = Math.max(post.value.likeCount - 1, 0)
    } else {
      await postApi.like(postId)
      post.value.liked = true
      post.value.likeCount += 1
    }
  } catch (error) {
    showError(error)
  } finally {
    interacting.value = false
  }
}

async function toggleCollect() {
  if (!requireLogin('收藏')) return
  interacting.value = true
  try {
    if (post.value.collected) {
      await postApi.uncollect(postId)
      post.value.collected = false
      post.value.collectCount = Math.max(post.value.collectCount - 1, 0)
    } else {
      await postApi.collect(postId)
      post.value.collected = true
      post.value.collectCount += 1
    }
  } catch (error) {
    showError(error)
  } finally {
    interacting.value = false
  }
}

function startReply(comment) {
  if (!requireLogin('回复')) return
  replyTo.value = comment
}
function cancelReply() {
  replyTo.value = null
}

async function submitComment() {
  const content = commentText.value.trim()
  if (!content) {
    ElMessage.warning('评论内容不能为空')
    return
  }
  if (!requireLogin('评论')) return

  submitting.value = true
  try {
    // 不回复任何人时传 null，后端把它当顶层评论
    await commentApi.create(postId, { parentId: replyTo.value?.id ?? null, content })
    commentText.value = ''
    replyTo.value = null
    commentPage.value = 1
    await loadComments()
    ElMessage.success('已发布')
  } catch (error) {
    showError(error)
  } finally {
    submitting.value = false
  }
}

function goTag(tagId) {
  router.push({ name: 'post-list', query: { tagId } })
}

onMounted(async () => {
  await loadPost()
  if (!notFound.value) {
    await loadComments()
  }
})
</script>

<template>
  <div class="wt-container">
    <!-- 帖子不存在 / 不可见：给明确的空页面，而不是一直转圈 -->
    <el-empty v-if="notFound" description="帖子不存在，或已被删除 / 下架">
      <el-button @click="router.push('/')">回首页</el-button>
    </el-empty>

    <template v-else>
      <el-card v-loading="loading" shadow="never" class="wt-post">
        <template v-if="post">
          <!-- 被下架时给作者一句明说：不提示的话，他只会觉得这篇帖子"怪怪的" -->
          <el-alert
            v-if="post.offline"
            type="warning"
            :closable="false"
            show-icon
            title="这篇帖子已被下架，只有你和管理员能看到"
            class="wt-post__alert"
          />

          <h1 class="wt-post__title">
            <el-tag v-if="post.top" type="danger" size="small" effect="dark">置顶</el-tag>
            <el-tag v-if="post.featured" type="warning" size="small" effect="dark">精华</el-tag>
            {{ post.title }}
          </h1>

          <div class="wt-post__meta">
            <span>{{ post.author?.nickname || '已注销用户' }}</span>
            <span>{{ formatTime(post.createTime) }}</span>
            <span>浏览 {{ formatCount(post.viewCount) }}</span>
          </div>

          <div v-if="post.tags?.length" class="wt-post__tags">
            <el-tag
              v-for="tag in post.tags"
              :key="tag.id"
              effect="plain"
              class="wt-post__tag"
              @click="goTag(tag.id)"
            >
              {{ tag.name }}
            </el-tag>
          </div>

          <!-- 正文按 Markdown 文本存（设计稿 §6），这里**按纯文本显示**：
               不引 Markdown 渲染库，避免把用户输入当 HTML 注入 -->
          <div class="wt-post__content">{{ post.content }}</div>

          <div class="wt-post__actions">
            <el-button
              :type="post.liked ? 'primary' : 'default'"
              :loading="interacting"
              @click="toggleLike"
            >
              点赞 {{ formatCount(post.likeCount) }}
            </el-button>
            <el-button
              :type="post.collected ? 'warning' : 'default'"
              :loading="interacting"
              @click="toggleCollect"
            >
              收藏 {{ formatCount(post.collectCount) }}
            </el-button>
          </div>
        </template>
      </el-card>

      <!-- 评论区 -->
      <el-card shadow="never" class="wt-comments">
        <template #header>
          <span class="wt-comments__title">评论 {{ formatCount(commentTotal) }}</span>
        </template>

        <!-- 发评论 / 回复 -->
        <div class="wt-editor">
          <div v-if="replyTo" class="wt-editor__reply">
            正在回复 <b>{{ replyTo.author?.nickname || '已注销用户' }}</b>
            <el-link type="info" :underline="false" @click="cancelReply">取消</el-link>
          </div>
          <el-input
            v-model="commentText"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            :placeholder="replyTo ? '回复……' : '说点什么……'"
          />
          <div class="wt-editor__foot">
            <el-button type="primary" :loading="submitting" @click="submitComment">发布</el-button>
          </div>
        </div>

        <div v-loading="commentLoading" class="wt-comment-list">
          <div v-for="comment in comments" :key="comment.id" class="wt-comment">
            <div class="wt-comment__head">
              <span class="wt-comment__author">{{ comment.author?.nickname || '已注销用户' }}</span>
              <span class="wt-comment__time">{{ formatTime(comment.createTime) }}</span>
              <el-link type="primary" :underline="false" @click="startReply(comment)">回复</el-link>
            </div>
            <div class="wt-comment__body">{{ comment.content }}</div>

            <!-- 回复缩进一级。后端是两级模型（ADR-0018）：回复的回复也会被拍平到这一层 -->
            <div v-if="comment.replies?.length" class="wt-comment__replies">
              <div v-for="reply in comment.replies" :key="reply.id" class="wt-reply">
                <div class="wt-comment__head">
                  <span class="wt-comment__author">{{ reply.author?.nickname || '已注销用户' }}</span>
                  <span class="wt-comment__time">{{ formatTime(reply.createTime) }}</span>
                  <el-link type="primary" :underline="false" @click="startReply(reply)">回复</el-link>
                </div>
                <div class="wt-comment__body">{{ reply.content }}</div>
              </div>
            </div>
          </div>

          <el-empty v-if="!commentLoading && comments.length === 0" description="还没有评论" />
        </div>

        <div v-if="commentTotal > commentSize" class="wt-pager">
          <el-pagination
            layout="prev, pager, next"
            :total="commentTotal"
            :page-size="commentSize"
            :current-page="commentPage"
            background
            @current-change="(p) => { commentPage = p; loadComments() }"
          />
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.wt-post {
  margin-bottom: var(--wt-gap);
}

.wt-post__alert {
  margin-bottom: var(--wt-gap);
}

.wt-post__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  font-size: 22px;
  line-height: 1.4;
  color: #303133;
}

.wt-post__meta {
  display: flex;
  gap: var(--wt-gap);
  font-size: 13px;
  color: #909399;
}

.wt-post__tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.wt-post__tag {
  cursor: pointer;
}

.wt-post__content {
  margin: var(--wt-gap-lg) 0;
  font-size: 15px;
  line-height: 1.8;
  color: #303133;
  /* 保留正文里的换行——正文是纯文本存进来的，不保留的话整篇会挤成一坨 */
  white-space: pre-wrap;
  word-break: break-word;
}

.wt-post__actions {
  display: flex;
  gap: var(--wt-gap);
  justify-content: center;
  padding-top: var(--wt-gap);
  border-top: 1px solid #ebeef5;
}

.wt-comments__title {
  font-weight: 600;
}

.wt-editor {
  margin-bottom: var(--wt-gap-lg);
}

.wt-editor__reply {
  margin-bottom: 8px;
  font-size: 13px;
  color: #606266;
  display: flex;
  align-items: center;
  gap: 8px;
}

.wt-editor__foot {
  margin-top: 8px;
  text-align: right;
}

.wt-comment {
  padding: var(--wt-gap) 0;
  border-bottom: 1px solid #f2f3f5;
}

.wt-comment:last-child {
  border-bottom: none;
}

.wt-comment__head {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.wt-comment__author {
  font-weight: 600;
  color: #303133;
}

.wt-comment__time {
  color: #c0c4cc;
}

.wt-comment__body {
  margin-top: 6px;
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 回复缩进一级——只用左边框与内边距，不用大幅度的 margin：
   缩进太深会让长回复的每一行都很短，读起来反而累 */
.wt-comment__replies {
  margin: var(--wt-gap) 0 0 var(--wt-gap-lg);
  padding-left: var(--wt-gap);
  border-left: 2px solid #ebeef5;
}

.wt-reply + .wt-reply {
  margin-top: var(--wt-gap);
}

.wt-pager {
  display: flex;
  justify-content: center;
  padding-top: var(--wt-gap);
}
</style>
