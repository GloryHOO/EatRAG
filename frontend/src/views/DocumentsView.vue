<script setup>
/**
 * 文档列表页面
 * 展示知识库全部食谱：统计概览、筛选、分页表格、详情抽屉、重建索引入口
 * 通过 /documents/:parentId 进入时直接打开对应文档详情
 */
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchDocuments, fetchStats, rebuildIndex } from '../api/knowledge'
import StatsOverview from '../components/StatsOverview.vue'
import DocumentDetailDrawer from '../components/DocumentDetailDrawer.vue'

// 路由传入的 parentId（用于直达详情）
const props = defineProps({
  parentId: {
    type: String,
    default: ''
  }
})

// 分类选项（与后端 Category 枚举一致）
const categoryOptions = ['荤菜', '素菜', '汤品', '甜品', '早餐', '主食', '水产', '调料', '饮品']
// 难度选项
const difficultyOptions = ['非常简单', '简单', '中等', '困难', '非常困难', '未知']

// 统计信息
const stats = ref(null)

// 筛选条件
const filters = reactive({
  category: '',
  difficulty: '',
  keyword: ''
})

// 列表数据
const loading = ref(false)
const documents = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

// 详情抽屉
const drawerVisible = ref(false)
const activeParentId = ref('')

// 重建索引进度
const rebuilding = ref(false)

// 难度标签颜色
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
 * 加载统计信息
 */
async function loadStats() {
  try {
    stats.value = await fetchStats()
  } catch (err) {
    ElMessage.error('加载统计信息失败: ' + err.message)
  }
}

/**
 * 加载文档列表（应用当前筛选与分页）
 */
async function loadDocuments() {
  loading.value = true
  try {
    const data = await fetchDocuments({
      category: filters.category || undefined,
      difficulty: filters.difficulty || undefined,
      keyword: filters.keyword || undefined,
      page: page.value,
      size: size.value
    })
    documents.value = data.items || []
    total.value = data.total || 0
  } catch (err) {
    ElMessage.error('加载文档列表失败: ' + err.message)
  } finally {
    loading.value = false
  }
}

/**
 * 查询：重置到第一页并加载
 */
function handleSearch() {
  page.value = 1
  loadDocuments()
}

/**
 * 重置筛选条件
 */
function handleReset() {
  filters.category = ''
  filters.difficulty = ''
  filters.keyword = ''
  page.value = 1
  loadDocuments()
}

/**
 * 分页变化
 */
function handlePageChange(p) {
  page.value = p
  loadDocuments()
}

function handleSizeChange(s) {
  size.value = s
  page.value = 1
  loadDocuments()
}

/**
 * 点击行打开详情抽屉
 */
function openDetail(row) {
  activeParentId.value = row.parentId
  drawerVisible.value = true
}

/**
 * 重建索引（带确认弹窗）
 */
async function handleRebuild() {
  try {
    await ElMessageBox.confirm(
      '重建索引会重新加载全部食谱并向量化，耗时取决于文档数量与 embedding 速度。确认继续？',
      '重建索引',
      { confirmButtonText: '确认重建', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return // 用户取消
  }

  rebuilding.value = true
  try {
    const result = await rebuildIndex()
    ElMessage.success(`索引重建完成：${result.totalDocuments} 篇文档，耗时 ${result.durationMs}ms`)
    // 重建后刷新统计与列表
    await loadStats()
    await loadDocuments()
  } catch (err) {
    ElMessage.error('重建索引失败: ' + err.message)
  } finally {
    rebuilding.value = false
  }
}

onMounted(() => {
  loadStats()
  loadDocuments()
  // 若通过路由 param 进入，打开详情
  if (props.parentId) {
    activeParentId.value = props.parentId
    drawerVisible.value = true
  }
})

// 监听路由 param 变化（切换详情）
watch(() => props.parentId, (pid) => {
  if (pid) {
    activeParentId.value = pid
    drawerVisible.value = true
  }
})
</script>

<template>
  <div class="documents-page">
    <!-- 统计概览 -->
    <StatsOverview :stats="stats" />

    <!-- 筛选区 -->
    <section class="filter-section">
      <el-form inline>
        <el-form-item label="分类">
          <el-select v-model="filters.category" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="难度">
          <el-select v-model="filters.difficulty" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="d in difficultyOptions" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="菜名">
          <el-input
            v-model="filters.keyword"
            placeholder="输入菜名关键词"
            clearable
            style="width: 180px"
            @keydown.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 重建索引入口（右下角低调入口） -->
      <el-button
        class="rebuild-btn"
        text
        type="primary"
        :loading="rebuilding"
        @click="handleRebuild"
      >
        {{ rebuilding ? '重建中…' : '重建索引' }}
      </el-button>
    </section>

    <!-- 文档列表 -->
    <section class="list-section">
      <el-table :data="documents" v-loading="loading" style="width: 100%" @row-click="openDetail">
        <el-table-column prop="dishName" label="菜名" min-width="160">
          <template #default="{ row }">
            <span class="dish-link">{{ row.dishName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="100">
          <template #default="{ row }">
            <el-tag size="small" type="info" effect="plain">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="difficulty" label="难度" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="difficultyTagType(row.difficulty)" effect="plain">
              {{ row.difficulty }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源路径" min-width="220">
          <template #default="{ row }">
            <span class="source-path">{{ row.source }}</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination">
        <el-pagination
          :current-page="page"
          :page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </section>

    <!-- 详情抽屉 -->
    <DocumentDetailDrawer v-model="drawerVisible" :parent-id="activeParentId" />
  </div>
</template>

<style scoped>
.documents-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.filter-section {
  background: #fff;
  padding: 16px 20px;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rebuild-btn {
  flex-shrink: 0;
}
.list-section {
  background: #fff;
  padding: 16px 20px;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}
.dish-link {
  color: var(--el-color-primary);
  font-weight: 500;
  cursor: pointer;
}
.source-path {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
:deep(.el-table__row) {
  cursor: pointer;
}
</style>