import { createApp } from 'vue'
// 引入 Element Plus 组件库及其样式
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

// 引入全局主题样式（覆盖 Element Plus 主色调）
import './assets/theme.css'

// 引入路由
import router from './router'

// 根组件
import App from './App.vue'

// 创建应用并挂载
createApp(App).use(ElementPlus).use(router).mount('#app')