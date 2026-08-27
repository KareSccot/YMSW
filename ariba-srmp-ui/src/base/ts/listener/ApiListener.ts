import {InternalAxiosRequestConfig} from "axios";
import {MsgUtils} from "@/base/ts/utils/MsgUtils.ts";

//监听执行器
let apiListener: (config: InternalAxiosRequestConfig) => void

//设置监听器
export const setApiListener = (doAnything: (config: InternalAxiosRequestConfig) => void) => {
  apiListener = doAnything;
}

//执行监听器
export function doApiListener(config: InternalAxiosRequestConfig) {
  if (apiListener) {
    apiListener(config);
  }
  // 显示蒙层
  if (config.headers?.isLoading) {
    MsgUtils.loading();
  }
}