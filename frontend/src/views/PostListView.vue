<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { postApi, tagApi } from '../api'
import { showError } from '../api/client'
import { formatCount, formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const posts = ref([])
const total = ref(0)
const tags = ref([])

const query = reactive({
  page: 1,
  size: 10,
  sort: 'LATEST',
  tagId: null
})

async function loadPosts() {
  loading.value = true
  try {
    const result = await postApi.page({ ...query })
    posts.value = result.items
    total.value = result.total
  } catch (error) {
    showError(error)
    posts.value = []
    total.value = 0
  } finally {
    // 必须放在 finally：出错时不关掉的话，页面会一直转圈，
    // 而用户看到的是"加载中"，完全不知道已经失败了
    loading.value = false
  }
}

async function loadTags() {
  try {
    tags.value = await tagApi.list(20)
  } catch (error) {
    // 标签加载失败不该让整个首页崩掉：帖子列表才是主体
    showError(error)
  }
}

function selectTag(tagId) {
  query.tagId = query.tagId === tagId ? null : tagId
  query.page = 1
}

function changeSort(sort) {
  query.sort = sort
  query.page = 1
}

function onPageChange(page) {
  query.page = page
}

// 筛选条件一变就重新拉数据。放在 watch 里而不是每个交互里手动调 loadPosts：
// 漏调一处就是"点了没反应"，而那种 bug 在页面上很难一眼看出
watch(query, loadPosts)

onMounted(() => {
  loadTags()
  // 支持从别处带着标签进来：/?tagId=3
  if (route.query.tagId) {
    query.tagId = Number(route.query.tagId)
  } else {
    loadPosts()
  }
})
</script>

<template>
  <div class="wt-container">
    <!-- 筛选区：排序与标签都放在列表上方，位置固定，翻页时不跟着动 -->
    <div class="wt-filter">
      <el-radio-group :model-value="query.sort" size="small" @change="changeSort">
        <el-radio-button value="LATEST">最新</el-radio-button>
        <el-radio-button value="HOT">最热</el-radio-button>
      </el-radio-group>

      <div v-if="tags.length" class="wt-filter__tags">
        <el-tag
          v-for="tag in tags"
          :key="tag.id"
          :effect="query.tagId === tag.id ? 'dark' : 'plain'"
          class="wt-filter__tag"
          @click="selectTag(tag.id)"
        >
          {{ tag.name }}
        </el-tag>
      </div>
    </div>

    <!-- 加载态：首屏与翻页都显示，用户才知道"点了有反应" -->
    <div v-loading="loading" class="wt-list">
      <el-card
        v-for="post in posts"
        :key="post.id"
        shadow="hover"
        class="wt-card"
        @click="router.push(`/posts/${post.id}`)"
      >
        <div class="wt-card__head">
          <el-tag v-if="post.top" type="danger" size="small" effect="dark">置顶</el-tag>
          <el-tag v-if="post.featured" type="warning" size="small" effect="dark">精华</el-tag>
          <span class="wt-card__title">{{ post.title }}</span>
        </div>

        <p class="wt-card__summary">{{ post.summary }}</p>

        <div class="wt-card__meta">
          <span>{{ post.author?.nickname || '已注销用户' }}</span>
          <span>{{ formatTime(post.createTime) }}</span>
          <span class="wt-card__stats">
            <span>浏览 {{ formatCount(post.viewCount) }}</span>
            <span>点赞 {{ formatCount(post.likeCount) }}</span>
            <span>评论 {{ formatCount(post.commentCount) }}</span>
          </span>
        </div>
      </el-card>

      <!-- 空状态：**没有数据时不能是一片白**，用户会以为是加载失败 -->
      <el-empty
        v-if="!loading && posts.length === 0"
        class="wt-empty"
        :description="query.tagId ? '这个标签下还没有帖子' : '还没有帖子，来发第一篇吧'"
      >
        <el-button type="primary" @click="router.push('/posts/new')">去发帖</el-button>
      </el-empty>
    </div>

    <div v-if="total > query.size" class="wt-pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.size"
        :current-page="query.page"
        background
        @current-change="onPageChange"
      />
    </div>
  </div>
</template>

<style scoped>
.wt-filter {
  display: flex;
  align-items: center;
  gap: var(--wt-gap-lg);
  flex-wrap: wrap;
  margin-bottom: var(--wt-gap);
}

.wt-filter__tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.wt-filter__tag {
  cursor: pointer;
}

.wt-card {
  margin-bottom: var(--wt-gap);
  cursor: pointer;
}

.wt-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.wt-card__title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

/* 摘要最多两行、超出省略：列表要的是"一眼扫过去"，
   让某一条把卡片撑得很高，整页的节奏就乱了 */
.wt-card__summary {
  margin: 0 0 12px;
  color: #606266;
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.wt-card__meta {
  display: flex;
  align-items: center;
  gap: var(--wt-gap);
  font-size: 13px;
  color: #909399;
}

.wt-card__stats {
  display: flex;
  gap: 12px;
  margin-left: auto;
}

.wt-pager {
  display: flex;
  justify-content: center;
  padding: var(--wt-gap) 0 var(--wt-gap-lg);
}
</style>
