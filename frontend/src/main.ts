import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import 'element-plus/dist/index.css'
// 暗色主题变量：Element Plus 官方方案，切换 html.dark 即生效
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/index.scss'

import App from './App.vue'
import router from './router'
import { useAppStore } from './stores/app'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 全局注册图标：模板里直接写 <el-icon><DataBoard /></el-icon>
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

// 统一异常兜底：组件渲染异常不应让页面白屏无提示
app.config.errorHandler = (error, _instance, info) => {
  console.error('[全局异常]', info, error)
  ElMessage.error('页面出现异常，请刷新后重试')
}

// 初始化主题（store 内部的 watch 会立即执行一次）
useAppStore()

app.mount('#app')
