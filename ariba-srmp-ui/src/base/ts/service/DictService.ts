import {stores} from "@/base/stores";
import sysDictItemApi, {DictItem} from "@/base/ts/api/biz/SysDictItemApi.ts";

export const initDict = () => {
  if (!stores().user.userInfo?.token) {
    return;
  }
  sysDictItemApi.getList().success(result => {
    result.data?.forEach((value, index) => {
      //强制布尔类型转换
      if (value.value === 'true') {
        // @ts-ignore
        value.value = true;
      }
      if (value.value === 'false') {
        // @ts-ignore
        value.value = false;
      }
    })
    stores().cache.dictItems = result.data;
  }).request();
}


export function getDictLabel(code: string, value: string) {
  return stores().cache.dictItems?.filter(dictItem => dictItem.code === code && dictItem.value == value)[0]?.label || '';
}

export function  getSelectDictItemByCode(code: string):DictItem[] {
  return stores().cache.dictItems?.filter(dictItem => dictItem.code === code).sort((a, b) => a.sort! - b.sort!)||[];
}