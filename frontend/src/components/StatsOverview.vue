<script setup>
/**
 * 统计概览组件
 * 展示知识库统计信息：文档总数、文本块总数、分类分布、难度分布
 */
import { computed } from 'vue'

const props = defineProps({
  stats: {
    type: Object,
    default: null
    // { totalDocuments, totalChunks, categories: {分类:数量}, difficulties: {难度:数量}, indexReady }
  }
})

/** 分类分布数组（用于标签展示） */
const categories = computed(() => {
  const map = props.stats?.categories || {}
  return Object.entries(map).map(([name, count]) => ({ name, count }))
})

/** 难度分布数组 */
const difficulties = computed(() => {
  const map = props.stats?.difficulties || {}
  return Object.entries(map).map(([name, count]) => ({ name, count }))
})

/** 难度标签颜色映射 */
const difficultyTagType = (name) => {
  const map = {
    '非常简单': 'success',
    '简单': 'success',
    '中等': 'warning',
    '困难': 'danger',
    '非常困难': 'danger',
    '未知': 'info'
  }
  return map[name] || 'info'
}
</script>

<template>
  <div v-if="stats" class="stats-overview">
    <!-- 统计卡片 -->
    <div class="stat-cards">
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ stats.totalDocuments }}</div>
        <div class="stat-label">文档总数</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ stats.totalChunks }}</div>
        <div class="stat-label">文本块总数</div>
      </el-card>
    </div>

    <!-- 分类分布 -->
    <div class="dist-section">
      <div class="dist-title">分类分布</div>
      <div class="dist-tags">
        <el-tag v-for="c in categories" :key="c.name" class="dist-tag" effect="plain" size="small">
          {{ c.name }}（{{ c.count }}）
        </el-tag>
      </div>
    </div>

    <!-- 难度分布 -->
    <div class="dist-section">
      <div class="dist-title">难度分布</div>
      <div class="dist-tags">
        <el-tag
          v-for="d in difficulties"
          :key="d.name"
          class="dist-tag"
          :type="difficultyTagType(d.name)"
          effect="plain"
          size="small"
        >
          {{ d.name }}（{{ d.count }}）
        </el-tag>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stats-overview {
  background: #fff;
  padding: 16px;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}
.stat-cards {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}
.stat-card {
  flex: 1;
  text-align: center;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--el-color-primary);
}
.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.dist-section {
  margin-top: 12px;
}
.dist-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.dist-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.dist-tag {
  margin: 0;
}
</style>