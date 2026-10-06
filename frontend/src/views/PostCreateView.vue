<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { postApi, tagApi } from '../api'
import { showError } from '../api/client'

const router = useRouter()

const submitting = ref(false)
const formRef = ref()
const tagOptions = ref([])

/** 标签上限与后端一致（`TagService.MAX_TAGS_PER_POST`）。 */
const MAX_TAGS = 5

const form = reactive({
  title: '',
  content: '',
  tags: []
})

const rules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 100, message: '标题最多 100 个字', trigger: 'blur' }
  ],
  content: [{ required: true, message: '请输入正文', trigger: 'blur' }]
}

onMounted(async () => {
  try {
    // 已有的标签作为候选项，但**不限制只能选这些**——
    // select 开了 allow-create，输入新词回车就会成为新标签
    tagOptions.value = await tagApi.list(50)
  } catch (error) {
    // 候选标签拉不到不该挡住发帖：用户照样可以自己输入
    showError(error)
  }
})

async function onSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const id = await postApi.create({
      title: form.title,
      content: form.content,
      tags: form.tags
    })
    ElMessage.success('发布成功')
    router.replace(`/posts/${id}`)
  } catch (error) {
    showError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="wt-container">
    <el-card shadow="never">
      <template #header>
        <span class="wt-create__title">发帖</span>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="一句话说清这篇讲什么" />
        </el-form-item>

        <el-form-item label="标签">
          <el-select
            v-model="form.tags"
            multiple
            filterable
            allow-create
            default-first-option
            :multiple-limit="MAX_TAGS"
            placeholder="选已有的，或直接输入新的（回车创建）"
            class="wt-create__tags"
          >
            <el-option v-for="tag in tagOptions" :key="tag.id" :label="tag.name" :value="tag.name" />
          </el-select>
          <div class="wt-create__hint">最多 {{ MAX_TAGS }} 个。标签名不区分大小写，<code>Redis</code> 与 <code>redis</code> 是同一个。</div>
        </el-form-item>

        <el-form-item label="正文" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="16"
            placeholder="正文按纯文本保存，换行会原样保留"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">发布</el-button>
          <el-button @click="router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.wt-create__title {
  font-weight: 600;
}

/* 标签选择框不能跟着表单铺满：它边上要留出提示文字的位置 */
.wt-create__tags {
  width: 100%;
  max-width: 420px;
}

.wt-create__hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
