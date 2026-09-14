<script setup>
/**
 * 查询中间信息组件
 * 展示查询路由类型标签，以及查询重写前/后的对比
 */
defineProps({
  routeType: {
    type: String,
    default: ''
  },
  originalQuery: {
    type: String,
    default: ''
  },
  rewrittenQuery: {
    type: String,
    default: ''
  }
})

/** 路由类型 → 标签文案与颜色 */
const routeMeta = (type) => {
  const map = {
    list: { label: '列表查询', type: 'info' },
    detail: { label: '详细查询', type: 'primary' },
    general: { label: '一般查询', type: 'warning' }
  }
  return map[type] || { label: type || '未知', type: 'info' }
}
</script>

<template>
  <div v-if="routeType" class="query-trace">
    <div class="trace-row">
      <span class="trace-label">路由类型：</span>
      <el-tag :type="routeMeta(routeType).type" size="small">
        {{ routeMeta(routeType).label }}
      </el-tag>
    </div>
    <div v-if="rewrittenQuery && rewrittenQuery !== originalQuery" class="trace-row">
      <span class="trace-label">查询重写：</span>
      <span class="trace-query">{{ originalQuery }}</span>
      <span class="trace-arrow">→</span>
      <span class="trace-query rewritten">{{ rewrittenQuery }}</span>
    </div>
  </div>
</template>

<style scoped>
.query-trace {
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  margin-bottom: 14px;
}
.trace-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.trace-row + .trace-row {
  margin-top: 6px;
}
.trace-label {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.trace-query {
  color: var(--el-text-color-regular);
}
.trace-query.rewritten {
  color: var(--el-color-primary);
  font-weight: 500;
}
.trace-arrow {
  color: var(--el-text-color-secondary);
}
</style>