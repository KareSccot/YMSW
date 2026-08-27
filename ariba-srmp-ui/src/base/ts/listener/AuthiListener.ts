import {LoginDto} from "@/base/ts/api/biz/AuthApi.ts";
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";

//登录成功监听执行器
let loginListener: (loginDto: LoginDto) => any;
//设置监听器
export const setLoginListener = (doAnything: (loginDto: LoginDto) => any) => {
  loginListener = doAnything;
}
//执行登录成功监听器
export function doLoginListener(loginDto: LoginDto):boolean {
  if (loginListener) {
    loginListener(loginDto);
  }
  return ObjectUtils.isNotEmpty(loginListener);
}

//退出登录监听器
let logoutListener: () => void
//设置退出登录监听器
export const setLogoutListener = (doAnything: () => void) => {
  logoutListener = doAnything;
}
//执行退出登录监听器
export function doLogoutListener():boolean {
  if (logoutListener) {
    logoutListener();
  }
  return ObjectUtils.isNotEmpty(logoutListener);
}

//未授权页面监听器
let notAuthorizedListener: () => void;
//设置未授权页面监听器
export const setNotAuthorizedListener = (doAnything: () => void) => {
  notAuthorizedListener = doAnything;
}
export function doNotAuthorizedListener():boolean {
  if (notAuthorizedListener) {
    notAuthorizedListener();
  }
  return ObjectUtils.isNotEmpty(notAuthorizedListener);
}

//清除缓存监听器
let cleanCacheListener: () => void;
//设置清除缓存监听器
export const setCleanCacheListener = (doAnything: () => void) => {
  cleanCacheListener = doAnything;
}
export function doCleanCacheListener():void {
  if (cleanCacheListener) {
    cleanCacheListener();
  }
}
