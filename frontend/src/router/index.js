import { createRouter, createWebHistory } from 'vue-router'

// 路由表（视图懒加载，按需分包）
const routes = [
  // 根路径重定向到智能搜索页
  { path: '/', redirect: '/search' },
  // 智能搜索页
  {
    path: '/search',
    name: 'Search',
    component: () => import('../views/SearchView.vue')
  },
  // 食谱库首页
  {
    path: '/documents',
    name: 'Documents',
    component: () => import('../views/DocumentsView.vue')
  },
  // 食谱库父文档详情页（复用 DocumentsView，通过 prop 传入 parentId）
  {
    path: '/documents/:parentId',
    name: 'DocumentDetail',
    component: () => import('../views/DocumentsView.vue'),
    // 将路由参数 parentId 作为 prop 传给组件
    props: (route) => ({ parentId: route.params.parentId })
  }
]

// 使用 HTML5 History 模式创建路由实例
const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router