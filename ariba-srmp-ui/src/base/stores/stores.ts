import {defineStore} from "pinia";
import {ref} from "vue";
import {LoginDto} from "@/base/ts/api/biz/AuthApi.ts";
import {DictItem} from "@/base/ts/api/biz/SysDictItemApi.ts";
import {SysMenu} from "@/base/ts/api/biz/SysMenuApi.ts";
import Header from "@/base/views/layout/header/header1.vue";
import Left from "@/base/views/layout/left/left1/left.vue";
import Tabs from "@/base/views/layout/tabs/tabs1.vue";
import Footer from "@/base/views/layout/footer/footer1.vue";
import Index from "@/base/views/layout/index/index1.vue";

//用户相关,登出会删除的部分
export const userStore = defineStore('userStore', {
  state: () => ({
    userInfo: ref<LoginDto>(),
    tabsMenus: ref<SysMenu[]>([]),
  }),
  persist: true, //持久化
})

//页面缓存
export const cacheStore = defineStore('cacheStore', {
  state: () => ({
    //是否保持登录
    isKeepLogin: false,
    //当前语言
    lang: 'zh-CN', //en-US英文 zh-CN中文 de-DE德语
    //语言包
    langPack: ref<Record<string, any>>(),
    //字典
    dictItems: ref<DictItem[]>(),
    //默认首页
    homePage: '/inside/index/index',
    //默认登录页
    loginPage: '/outside/login',
  }),
  persist: true,
  actions: {
    migrateHomePage() {
      if (this.homePage === '/index') {
        this.homePage = '/inside/index/index'
      }
    }
  }
})


//主题相关
export const themeStore  = defineStore('themeStore', {
  state: () => ({
    //主题类型
    type: 'default',
    //设置默认的displayType, fixed 固定, auto 自适应,不设置则默认为空,为auto
    displayType:'',
    //设计图分辨率宽度
    designWidth: 1580,
    //菜单是否折叠
    isCollapse: false,
  }),
  persist: true,
})

//布局组件
export const noCacheStore  = defineStore('noCacheStore', {
  state: () => ({
    //自定义组件
    layout: ref<any>({
      index: Index,
      header: Header,
      left: Left,
      tabs: Tabs,
      footer: Footer,
      customerSolt: ref<any>({}),
    })
  })
})


