//页面路由监听

//监听执行器
import {NavigationGuardNext, RouteLocationNormalizedGeneric, RouteLocationNormalizedLoadedGeneric} from "vue-router";
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";

let routerListener: (to: RouteLocationNormalizedGeneric, from: RouteLocationNormalizedLoadedGeneric, next: NavigationGuardNext) => void;

// 设置路由监听器
export const setRouterListener = (doAnything: (to: RouteLocationNormalizedGeneric, from: RouteLocationNormalizedLoadedGeneric, next: NavigationGuardNext) => void) => {
  routerListener = doAnything;
}

// 执行路由监听器
export function doRouterListener(to: RouteLocationNormalizedGeneric, from: RouteLocationNormalizedLoadedGeneric, next: NavigationGuardNext):boolean {
  if (routerListener) {
    routerListener(to, from, next);
  }
  return ObjectUtils.isNotEmpty(routerListener);
}
