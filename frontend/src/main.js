import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/global.css'

// 用 Element Plus 的**中文语言包**：默认是英文，分页控件会显示 "Go to"、
// 表单校验的默认文案也是英文——不设的话界面上会中英混杂。
createApp(App)
  .use(ElementPlus, { locale: zhCn })
  .use(router)
  .mount('#app')
