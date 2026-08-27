import type {Router} from "vue-router";
import {stores} from "@/base/stores";
import {getMenuByPath, logout, notAuthorized} from "@/base/ts/service/AuthService.ts";
import {doRouterListener} from "@/base/ts/listener/RouterListener.ts";

export default function (router: Router) {
    router.beforeEach((to, from, next) => {

        // 执行路由监听器,有监听者则不执行默认
        if (doRouterListener(to, from, next)) {
            return;
        }

        //不是outside的页面直接放行
        if (to.path?.startsWith('/outside/')) {
            next();
            return;
        }

        //未登录或以login结尾,跳转登录页面
        if (!stores().user.userInfo?.token || to.path.endsWith('/login')) {
            logout();
            return;
        }

        //未授权页面（默认首页和根路径跳过权限检查）
        if (!to.path.endsWith('/404') && !to.path.endsWith('/403') && to.path !== '/' && to.path !== stores().cache.homePage && !getMenuByPath(to.path)) {
            notAuthorized()
            return;
        }
        next();
    })
}