// @ts-ignore
import {UserUtils} from "@/base/ts/utils/UserUtils.ts";
import {SysUser} from "@/base/ts/api/biz/SysUserApi.ts";
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";
import {Utils} from "@/base/ts/utils/Utils.ts";
import {getDictLabel, getSelectDictItemByCode} from "@/base/ts/service/DictService.ts";
import {LoginDto, LoginVo} from "@/base/ts/api/biz/AuthApi.ts";
import {clearCache, login, loginEnd, logout, samlLogin} from "@/base/ts/service/AuthService.ts";
import {stores} from "@/base/stores";
import {i18n} from "@/base/ts/service/I18nService.ts";
import {env} from "@/base/ts/utils/env.ts";
import router from "@/base/router";

// @ts-ignore
let amisLib = amisRequire('amis');

// @ts-ignore
declare global {
  interface Window {
    g: any;
  }
}
//初始化全局变量
if (!window.g) {
  window.g = {};
}
/**
 * 以下的方法或者属性在原生页面或者AMIS页面都可以使用
 * 使用方法:AMIS自定义和本地JS  window.g.方法名()
 * 使用方法:tpl <%= ${window.g.方法名()} %>
 * 使用方法:tpl ${方法名()}
 */

//功能权限
window.g.auth = (data: any) => UserUtils.hasPermission(data);
amisLib.registerFunction('auth', (data: any) => UserUtils.hasPermission(data));

//国际化
window.g.i18n = (data: string) => i18n(data);
amisLib.registerFunction('i18n', (data: string) => i18n(data));

//缓存
amisLib.registerFunction('stores', () => stores());
window.g.stores = () => stores();

//环境变量
window.g.env = (param:string) => env(param);
amisLib.registerFunction('env', (param:string) => env(param));

//将对象组装为url参数
window.g.toUrl = (obj: any) => Utils.objectToUrlParams(obj);
amisLib.registerFunction('toUrl', (obj: any) => Utils.objectToUrlParams(obj));

//router
window.g.router = () => router;
amisLib.registerFunction('routers', () =>  router.getRoutes().find(v => v.name === 'inside')?.children?.map(v => v.path));

//登录
window.g.login = (loginVo: LoginVo) => login(loginVo);
amisLib.registerFunction('login', (loginVo: LoginVo) => window.g.login(loginVo));

//登录完成
window.g.loginEnd = (loginDto: LoginDto) => loginEnd(loginDto);
amisLib.registerFunction('loginEnd', (loginDto: LoginDto) => window.g.loginEnd(loginDto));

//获取用户信息
window.g.userInfo = () => stores().user.userInfo;
amisLib.registerFunction('userInfo', () => stores().user.userInfo);

//清除缓存
window.g.clearCache = () => clearCache();
amisLib.registerFunction('clearCache', () => window.g.clearCache());

//退出登录
window.g.logout = () => logout();
amisLib.registerFunction('logout', () => window.g.logout())

//用户展示
window.g.viewUser = (user: SysUser | SysUser[]) => {
  if (ObjectUtils.isEmpty(user)) {
    return '';
  }
  // 如果是数组，就显示多个
  if (Array.isArray(user)) {
    return user.map(v => Utils.format("{}({})", v.name, v.account)).join(',');
  }
  // 如果是对象，就显示一个
  return user.name + '(' + user.account + ')';
}
//注册进AMIS
amisLib.registerFunction('viewUser', (user: SysUser | SysUser[]) => window.g.viewUser(user));

//获取字典项
window.g.getDictItems = (code: string) => getSelectDictItemByCode(code);
amisLib.registerFunction('getDictItems', (code: string) => getSelectDictItemByCode(code));

//字典值展示
window.g.viewDict = (code: string, value: string | string[]) => {
  if (ObjectUtils.isEmpty(code) || ObjectUtils.isEmpty(value)) {
    return '';
  }
  //如果是数组，就显示多个
  if (Array.isArray(value)) {
    return value.map(v => getDictLabel(code, v)).join(',');
  }
  //如果是字符串，就显示一个
  return getDictLabel(code, value);
}
amisLib.registerFunction('viewDict', (code: string, value: any) => window.g.viewDict(code, value));

//SSO登录
window.g.samlLogin = (relayState?: string) => samlLogin(relayState);
amisLib.registerFunction('samlLogin', (relayState?: string) => window.g.samlLogin(relayState));
