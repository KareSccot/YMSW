import http from "@/base/ts/api/base/axios.ts";
import {MsgUtils} from "@/base/ts/utils/MsgUtils.ts";
import {Utils} from "@/base/ts/utils/Utils.ts";
import router from "@/base/router";

export default {
  fetcher: ({url, method, data, config, headers, responseType}: any) => {
    config = config || {};
    config.headers = config.headers || headers || {};
    return http.request({...config, url: url, method: method, responseType: responseType, data: data, headers: headers});
  },

  jumpTo: (to:any, action:any) => {
    if (action && action.actionType === 'url' && action.blank === true) {
      //AMIS 新窗口打开url,解析是否http开始
      if (to.startsWith("http")) {
        window.open(to, '_blank');
        return;
      }
      window.open(router.resolve({path:to}).href, '_blank');
      return;
    }
    router.replace(to);
  },

  notify:(type: any, msg: string) => {
    MsgUtils.msg(type, msg);
  },

  confirm: async (msg: string) => {
    return MsgUtils.confirmAmis( msg);
  },

  copy:(contents: string, options?: {silent: boolean, format?: string})=>{
    MsgUtils.successMsg('已复制到粘贴板')
    Utils.toClipboard(contents)
  }
};