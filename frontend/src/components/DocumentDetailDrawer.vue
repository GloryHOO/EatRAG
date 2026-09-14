<script setup>
/**
 * 文档详情抽屉组件
 * 展示一份食谱的完整 Markdown 原文（渲染后），
 * 通过 v-model 控制显隐，传入 parentId 加载详情
 */
import { ref, watch, computed } from 'vue'
import MarkdownIt from 'markdown-it'
import { fetchDocumentDetail } from '../api/knowledge'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  parentId: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['update:modelValue'])

const md = new MarkdownIt()

const loading = ref(false)
const detail = ref(null)   // { parentId, dishName, category, difficulty, source, content }
const errorMsg = ref('')

// 是否显示（双向绑定）
const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

// 渲染后的 Markdown HTML
const renderedContent = computed(() => {
  return detail.value?.content ? md.render(detail.value.content) : ''
})

const difficultyTagType = (d) => {
  const map = {
    '非常简单': 'success',
    '简单': 'success',
    '中等': 'warning',
    '困难': 'danger',
    '非常困难': 'danger',
    '未知': 'info'
  }
  return map[d] || 'info'
}

/**
 * 根据 parentId 加载文档详情
 */
async function loadDetail() {
  if (!props.parentId) {
    return
  }
  loading.value = true
  errorMsg.value = ''
  detail.value = null
  try {
    detail.value = await fetchDocumentDetail(props.parentId)
  } catch (err) {
    errorMsg.value = err.message || '文档不存在'
  } finally {
    loading.value = false
  }
}

// 打开抽屉或有 parentId 时加载详情
watch(visible, (v) => {
  if (v && props.parentId) {
    loadDetail()
  }
})
watch(() => props.parentId, () => {
  if (visible.value && props.parentId) {
    loadDetail()
  }
})
</script>

<template>
  <el-drawer v-model="visible" :size="'46%'" destroy-on-close>
    <template #header>
      <div v-if="detail" class="drawer-header">
        <span class="drawer-title">{{ detail.dishName }}</span>
        <div class="drawer-meta">
          <el-tag size="small" type="info" effect="plain">{{ detail.category }}</el-tag>
          <el-tag size="small" :type="difficultyTagType(detail.difficulty)" effect="plain">
            {{ detail.difficulty }}
          </el-tag>
        </div>
      </div>
      <span v-else class="drawer-title">文档详情</span>
    </template>

    <div v-loading="loading" class="drawer-body">
      <el-alert
        v-if="errorMsg"
        :title="errorMsg"
        type="error"
        show-icon
        :closable="false"
      />
      <div v-else-if="detail" class="markdown-body" v-html="renderedContent"></div>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.drawer-title {
  font-size: 18px;
  font-weight: 600;
}
.drawer-meta {
  display: flex;
  gap: 6px;
}
.drawer-body {
  min-height: 200px;
  line-height: 1.7;
}
/* markdown 渲染基础样式 */
.markdown-body :deep(h1) {
  font-size: 20px;
  margin: 16px 0 10px;
}
.markdown-body :deep(h2) {
  font-size: 18px;
  margin: 14px 0 8px;
}
.markdown-body :deep(h3) {
  font-size: 16px;
  margin: 12px 0 6px;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 22px;
}
.markdown-body :deep(li) {
  margin: 4px 0;
}
.markdown-body :deep(img) {
  max-width: 100%;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 6px 10px;
}
</style>