import {stores} from "@/base/stores";
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";
import sysI18nApi from "@/base/ts/api/biz/SysI18nApi.ts";

export const initI18nPack = () => {
  if (!stores().user.userInfo?.token) {
    return;
  }
  sysI18nApi.getAll().success(result => {
    stores().cache.langPack = result.data;
  }).request();
}

export const getI18nPack = () => {
  const allPack = stores().cache.langPack;
  if (ObjectUtils.isEmpty(allPack)) {
    return;
  }
  return allPack![stores().cache.lang];
}

//多语言翻译
export const i18n = (context?: string) => {
  if (!context) {
    return context;
  }
  const allPack = stores().cache.langPack;
  if (ObjectUtils.isEmpty(allPack)) {
    return context;
  }
  const langPack = allPack![stores().cache.lang];

  if (ObjectUtils.isEmpty(langPack)) {
    return context;
  }
  if (ObjectUtils.isEmpty(langPack[context])) {
    return context;
  }
  return langPack[context];
}