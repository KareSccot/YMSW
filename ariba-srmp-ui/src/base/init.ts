import {initStyleTheme} from "@/base/ts/style.ts";
import {initDict} from "@/base/ts/service/DictService.ts";
import {refreshUserInfo} from "@/base/ts/service/AuthService.ts";
import {initI18nPack} from "@/base/ts/service/I18nService.ts";

//初始化
export const initBase = ()=>{
  //引入自定义样式
  initStyleTheme();

  //注册全局amis方法
  refreshUserInfo();

  //初始化数据字典
  initDict();

  //初始化多语言
  initI18nPack();
}