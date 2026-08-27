import {createApp} from 'vue'
import router from './base/router'
import App from './App.vue'
import pinia from "@/base/stores";
import '@/base/ts/amis/Global.ts'
import 'element-plus/dist/index.css'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {initBase} from "@/base/init.ts";
import {initCustom} from "@/custom/ts/init.ts";

const app = createApp(App)

app.use(ElementPlus, {locale: zhCn})
app.use(pinia)
app.use(router)
app.mount('#app')

//Base的各类初始化
initBase();
//Custom的各类初始化
initCustom();