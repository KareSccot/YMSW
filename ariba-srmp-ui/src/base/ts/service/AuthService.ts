import {MsgUtils} from "@/base/ts/utils/MsgUtils.ts";
import {stores} from "@/base/stores";
import router from "@/base/router";
import AuthApi, {LoginDto, LoginVo, SamlLoginDto} from "@/base/ts/api/biz/AuthApi.ts";
import {doCleanCacheListener, doLoginListener, doLogoutListener, doNotAuthorizedListener} from "@/base/ts/listener/AuthiListener.ts";
import {initDict} from "@/base/ts/service/DictService.ts";
import {initI18nPack} from "@/base/ts/service/I18nService.ts";
import http from "@/base/ts/api/base/axios.ts";
import {getApiUrl} from "@/base/ts/api/base/BaseApi.ts";

//登录
export function login(loginVo: LoginVo) {
  AuthApi.login(loginVo)
    .start(() => MsgUtils.loading())
    .end(() => MsgUtils.loading_close())
    .success(result => {
      loginEnd(result.data!);
    }).request()
}

//SAML SSO登录 - 跳转到Azure AD登录页
export function samlLogin(relayState?: string) {
  const url = AuthApi.samlLogin(relayState);
  window.location.href = url.toString().includes('http') ? url.toString() : `${window.location.origin}${url}`;
}

//SAML SSO回调处理
export function samlCallback(samlResponse: string, relayState?: string) {
  return new Promise<SamlLoginDto>((resolve, reject) => {
    AuthApi.samlAcs(samlResponse, relayState)
      .start(() => MsgUtils.loading())
      .end(() => MsgUtils.loading_close())
      .success(result => {
        if (result.data?.token) {
          const loginDto: LoginDto = {
            token: result.data.token,
            sysUser: {
              id: result.data.userId,
              nickName: result.data.userName,
              email: result.data.email,
            } as any,
            isAdmin: false,
            menuTree: [],
            menuList: [],
            roleList: [],
          };
          loginEnd(loginDto);
          resolve(result.data);
        } else {
          reject(new Error("SAML登录失败"));
        }
      })
      .fail(err => {
        reject(err);
      })
      .request();
  });
}

//获取SAML配置（/saml/config 返回原始 JSON，非标准 Result 格式，直接用 axios）
export async function getSamlConfig() {
  try {
    const url = getApiUrl('/saml', '/config');
    const response = await http.get(url);
    return response.data?.enabled ?? false;
  } catch {
    return false;
  }
}

//登录完成
export function loginEnd(loginDto: LoginDto, redirectPath?: string) {
  stores().user.userInfo = loginDto;
  //登录成功后清除 SSO 尝试标记，下次会话过期可再次自动 SSO
  sessionStorage.removeItem('sso_attempted');
  //初始化数据字典
  initDict();
  //初始化多语言
  initI18nPack();
  if (!doLoginListener(loginDto)) {
    //没有监听器,自动进入首页
    const target = redirectPath || stores().cache.homePage;
    router.replace(target.startsWith('/') ? target : '/' + target);
  }
}

//登录过期
export function loginDue() {
  MsgUtils.alert(() => {
    logout();
  }, "关闭或点击确认将重新登录", "登录过期提醒")
}

//退出登录
export function logout() {
  //清除缓存的用户信息
  const storeArr: any[] = [stores().user]
  storeArr.forEach(store => {
    Object.getOwnPropertyNames(store.$state).forEach((storeKey: string) => {
      store[storeKey] = undefined;
    })
  })
  if (!doLogoutListener()) {
    //没有监听器,回到登录页
    router.replace(stores().cache.loginPage);
  }
}

//未授权页面
export function notAuthorized() {
  //未授权页面监听器
  if (!doNotAuthorizedListener()) {
    //没有监听器,回到登录页
    router.replace(stores().cache.loginPage);
  }
  router.replace('/403');
}

//刷新用户信息
export function refreshUserInfo() {
  if (!stores().user.userInfo?.token) {
    return;
  }
  AuthApi.refreshUserInfo()
    .start(() => MsgUtils.loading())
    .end(() => MsgUtils.loading_close())
    .success(result => {
      stores().user.userInfo = result.data;
    }).request()
}

//清除缓存
export function clearCache() {
  //清除缓存的用户信息
  const storeArr: any[] = [stores().cache,stores().theme];
  storeArr.forEach(store => {
    Object.getOwnPropertyNames(store.$state).forEach((storeKey: string) => {
      store[storeKey] = undefined;
    })
  })
  //清除缓存监听器
  doCleanCacheListener();
  //刷新页面
  window.location.reload();
}

//获取当前路由对应的菜单
export function getMenuByPath(path: string) {
  return stores().user.userInfo?.menuList?.filter(menu => menu.path === path)[0];
}
//根据ID获取菜单
export function getMenuById(id: string) {
  return stores().user.userInfo?.menuList?.filter(menu => menu.id === id)[0];
}
