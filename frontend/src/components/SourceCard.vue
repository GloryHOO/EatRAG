<script setup>
/**
 * 来源卡片组件
 * 展示回答所引用的食谱（菜名、分类、难度），点击跳转到文档详情页
 */
defineProps({
  source: {
    type: Object,
    required: true
    // { parentId, dishName, category, difficulty }
  }
})

/** 难度标签颜色映射 */
const difficultyTagType = (difficulty) => {
  const map = {
    '非常简单': 'success',
    '简单': 'success',
    '中等': 'warning',
    '困难': 'danger',
    '非常困难': 'danger',
    '未知': 'info'
  }
  return map[difficulty] || 'info'
}
</script>

<template>
  <el-card
    class="source-card"
    shadow="hover"
    @click="$router.push(`/documents/${source.parentId}`)"
  >
    <div class="source-header">
      <span class="dish-name">{{ source.dishName }}</span>
    </div>
    <div class="source-tags">
      <el-tag size="small" type="info" effect="plain">{{ source.category }}</el-tag>
      <el-tag size="small" :type="difficultyTagType(source.difficulty)" effect="plain">
        {{ source.difficulty }}
      </el-tag>
    </div>
    <div class="source-hint">查看完整食谱 →</div>
  </el-card>
</template>

<style scoped>
.source-card {
  cursor: pointer;
  transition: transform 0.2s;
}
.source-card:hover {
  transform: translateY(-2px);
}
.source-header {
  margin-bottom: 8px;
}
.dish-name {
  font-weight: 600;
  font-size: 15px;
}
.source-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.source-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>