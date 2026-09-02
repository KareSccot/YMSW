import {AxiosError, InternalAxiosRequestConfig} from "axios";
import {MsgUtils} from "@/base/ts/utils/MsgUtils.ts";

//监听执行器
let apiEndListener: (config: InternalAxiosRequestConfig,error?:AxiosError) => void

//设置监听器
export const setApiEndListener = (doAnything: (config: InternalAxiosRequestConfig,error?:AxiosError) => void) => {
  apiEndListener = doAnything;
}

//执行监听器
export function doApiEndListener(config: any,error?:AxiosError) {
  if (apiEndListener) {
    apiEndListener(config,error);
  }
  // 关闭蒙层
  if (config.headers?.isLoading) {
    MsgUtils.loading_close();
  }
}