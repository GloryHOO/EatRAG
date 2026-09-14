<script setup>
// 根组件：全局布局
</script>

<template>
  <!-- 整体浅色背景 -->
  <div class="app-container">
    <!-- 顶部导航栏 -->
    <header class="app-header">
      <!-- 应用名 -->
      <div class="app-brand">🍳 尝尝咸淡</div>
      <!-- 横向导航菜单，router 模式，选中项跟随当前路由 -->
      <el-menu
        mode="horizontal"
        router
        :default-active="$route.path"
        class="app-menu"
        :ellipsis="false"
      >
        <el-menu-item index="/search">智能搜索</el-menu-item>
        <el-menu-item index="/documents">食谱库</el-menu-item>
      </el-menu>
    </header>

    <!-- 内容区：最大宽度 1200px 居中 -->
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
/* 整体布局：浅色背景，纵向排列 */
.app-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #fafafa;
}

/* 顶部导航栏：白底，带阴影，内边距 */
.app-header {
  display: flex;
  align-items: center;
  padding: 0 24px;
  background-color: #ffffff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  position: sticky;
  top: 0;
  z-index: 100;
}

/* 应用名样式 */
.app-brand {
  font-size: 22px;
  font-weight: 700;
  color: #e6762d;
  white-space: nowrap;
  margin-right: 32px;
}

/* 菜单不设底部边框，与导航栏无缝衔接 */
.app-menu {
  border-bottom: none;
}

/* 内容区：最大宽度 1200px 居中 */
.app-main {
  flex: 1;
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
  box-sizing: border-box;
}
</style>