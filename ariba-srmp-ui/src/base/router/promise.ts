import type {Router} from "vue-router";
import {stores} from "@/base/stores";
import {getMenuByPath, getSamlConfig, logout, notAuthorized, samlLogin} from "@/base/ts/service/AuthService.ts";
import {doRouterListener} from "@/base/ts/listener/RouterListener.ts";

/** sessionStorage 键名：标记当前会话是否已尝试过 SSO，防止循环重定向 */
const SSO_ATTEMPTED_KEY = 'sso_attempted';

/** SAML 启用状态缓存（每次页面加载只请求一次） */
let samlEnabledCached: boolean | null = null;

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

        //未登录
        if (!stores().user.userInfo?.token) {
            // 初始页面加载（用户手动访问根目录或刷新页面），清除 SSO 尝试标记，允许重新发起 SSO
            // from.matched.length === 0 表示是首次导航（Vue Router START_LOCATION）
            if (from.matched.length === 0) {
                sessionStorage.removeItem(SSO_ATTEMPTED_KEY);
            }

            // SSO 已尝试过（失败或取消），直接走登录页
            if (sessionStorage.getItem(SSO_ATTEMPTED_KEY)) {
                logout();
                return;
            }

            // SAML 配置尚未加载，取消当前导航，异步获取后重定向
            if (samlEnabledCached === null) {
                next(false);
                getSamlConfig().then(enabled => {
                    samlEnabledCached = enabled;
                    if (enabled) {
                        sessionStorage.setItem(SSO_ATTEMPTED_KEY, '1');
                        samlLogin(to.fullPath);
                    } else {
                        logout();
                    }
                });
                return;
            }

            // SAML 已启用，自动跳 SSO
            if (samlEnabledCached) {
                sessionStorage.setItem(SSO_ATTEMPTED_KEY, '1');
                samlLogin(to.fullPath);
                return;
            }

            // SAML 未启用，走登录页
            logout();
            return;
        }

        //已登录但访问登录页 → 登出
        if (to.path.endsWith('/login')) {
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
