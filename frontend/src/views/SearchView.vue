<script setup>
/**
 * 搜索页面
 * 输入自然语言问题，流式展示 RAG 回答、查询中间信息与引用来源卡片
 */
import { ref, computed, onBeforeUnmount } from 'vue'
import MarkdownIt from 'markdown-it'
import { chatStream } from '../api/chat'
import { fetchStats } from '../api/knowledge'
import SourceCard from '../components/SourceCard.vue'
import QueryTrace from '../components/QueryTrace.vue'

// Markdown 渲染器（用于最终回答的格式化展示）
const md = new MarkdownIt()

const question = ref('')
const answer = ref('')           // 流式累积的回答文本
const loaded = ref(false)        // 是否已完成一次问答
const streaming = ref(false)     // 是否正在流式生成中
const routeType = ref('')        // 查询路由类型
const originalQuery = ref('')    // 原始查询
const rewrittenQuery = ref('')   // 重写后的查询
const sources = ref([])          // 引用来源列表
const errorMsg = ref('')         // 错误信息
const indexReady = ref(true)     // 知识库索引是否就绪

let activeStream = null          // 当前活跃的 SSE 句柄

// 最终答案：完成后用 markdown 渲染，流式期间用纯文本
const renderedAnswer = computed(() => (loaded.value ? md.render(answer.value) : answer.value))

const exampleQuestions = ['宫保鸡丁怎么做', '推荐几个简单的素菜', '红烧肉需要什么食材']

/**
 * 页面加载时检查索引是否就绪
 */
async function checkIndex() {
  try {
    const stats = await fetchStats()
    indexReady.value = stats.indexReady !== false
  } catch {
    indexReady.value = false
  }
}
checkIndex()

/**
 * 提交问题，发起流式问答
 */
function submit() {
  const q = question.value.trim()
  if (!q || streaming.value || !indexReady.value) {
    return
  }

  // 重置状态
  answer.value = ''
  loaded.value = false
  routeType.value = ''
  originalQuery.value = q
  rewrittenQuery.value = ''
  sources.value = []
  errorMsg.value = ''
  streaming.value = true

  // 关闭上一个未结束的连接，避免串流
  if (activeStream) {
    activeStream.close()
  }

  activeStream = chatStream(q, {
    // 收到文本片段：追加到回答缓冲区
    onMessage(content) {
      answer.value += content
    },
    // 收到完成事件：设置路由类型、重写前后查询、来源列表
    onDone(payload) {
      routeType.value = payload.routeType || ''
      originalQuery.value = payload.originalQuery || q
      rewrittenQuery.value = payload.rewrittenQuery || ''
      sources.value = payload.sources || []
      loaded.value = true
      streaming.value = false
    },
    // 出错：保留已接收的内容，标记失败
    onError(err) {
      errorMsg.value = err.message
      streaming.value = false
      if (answer.value) {
        // 已有部分内容时仍可展示，标记为已加载
        loaded.value = true
      }
    }
  })
}

/**
 * 点击示例问题快捷填充并提交
 */
function useExample(q) {
  question.value = q
  submit()
}

onBeforeUnmount(() => {
  if (activeStream) {
    activeStream.close()
  }
})
</script>

<template>
  <div class="search-page">
    <!-- 提问区 -->
    <section class="ask-section">
      <el-input
        v-model="question"
        type="textarea"
        :rows="2"
        maxlength="200"
        show-word-limit
        placeholder="输入你的问题，例如：宫保鸡丁怎么做？"
        :disabled="!indexReady"
        @keydown.enter.exact.prevent="submit"
      />
      <div class="ask-actions">
        <el-button
          type="primary"
          size="large"
          :loading="streaming"
          :disabled="!indexReady"
          @click="submit"
        >
          {{ streaming ? '生成中…' : '提问' }}
        </el-button>
        <span v-if="!indexReady" class="index-tip">
          知识库索引尚未就绪，请稍后重试
        </span>
      </div>

      <!-- 空状态：示例问题引导 -->
      <div v-if="!streaming && !loaded && !errorMsg" class="examples">
        <span class="examples-label">试试：</span>
        <el-tag
          v-for="q in exampleQuestions"
          :key="q"
          class="example-tag"
          effect="plain"
          @click="useExample(q)"
        >
          {{ q }}
        </el-tag>
      </div>
    </section>

    <!-- 查询中间信息 -->
    <QueryTrace
      :route-type="routeType"
      :original-query="originalQuery"
      :rewritten-query="rewrittenQuery"
    />

    <!-- 错误提示 -->
    <el-alert
      v-if="errorMsg"
      :title="`回答中断：${errorMsg}`"
      type="warning"
      show-icon
      :closable="false"
      class="error-alert"
    />

    <!-- 回答区 -->
    <section v-if="loaded || streaming" class="answer-section">
      <el-card shadow="never">
        <template #header>
          <span class="answer-title">回答</span>
          <span v-if="streaming" class="streaming-indicator">生成中</span>
        </template>
        <!-- 流式期间纯文本，完成后 markdown 渲染 -->
        <div v-if="streaming" class="answer-text">{{ answer }}<span class="cursor">▌</span></div>
        <div v-else class="answer-text markdown-body" v-html="renderedAnswer"></div>
      </el-card>
    </section>

    <!-- 引用来源 -->
    <section v-if="sources.length" class="sources-section">
      <div class="sources-title">引用来源</div>
      <div class="sources-grid">
        <SourceCard v-for="(s, idx) in sources" :key="idx" :source="s" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.search-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.ask-section {
  background: #fff;
  padding: 20px;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}
.ask-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.index-tip {
  font-size: 13px;
  color: var(--el-color-warning);
}
.examples {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.examples-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.example-tag {
  cursor: pointer;
}
.error-alert {
  border-radius: 8px;
}
.answer-title {
  font-weight: 600;
}
.streaming-indicator {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-color-primary);
}
.answer-text {
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 15px;
}
.cursor {
  color: var(--el-color-primary);
  animation: blink 1s step-start infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
/* markdown 渲染结果的基础样式 */
.markdown-body :deep(h2) {
  margin: 16px 0 8px;
}
.markdown-body :deep(h3) {
  margin: 12px 0 6px;
}
.markdown-body :deep(ul) {
  padding-left: 20px;
}
.markdown-body :deep(li) {
  margin: 4px 0;
}
.sources-title {
  font-weight: 600;
  margin-bottom: 10px;
}
.sources-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}
</style>