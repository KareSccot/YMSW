import {ElMessageBox} from "element-plus/es";
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";
import {MsgUtils} from "@/base/ts/utils/MsgUtils.ts";
import type {Result} from "@/base/ts/api/base/BaseApi.ts";
import {loginDue} from "@/base/ts/service/AuthService.ts";
import {i18n} from "@/base/ts/service/I18nService.ts";
import {Utils} from "@/base/ts/utils/Utils.ts";

export function downloadErrorInfoHandle(res: any, errFunc?: any) {
  if (!res || !res.data) return;
  if (res.data.type && res.data.type === "application/json") {
    try {
      let reader: any = new FileReader(); // 创建读取文件对象
      reader.readAsText(res.data, "utf-8"); // 设置读取的数据以及返回的数据类型为utf-8
      reader.addEventListener("loadend", function () { //
        let res1: any = JSON.parse(reader.result); // 返回的数据
        let {code, msg} = res1;
        if (code === 500) {
          errFunc && errFunc(msg);
          MsgUtils.errorMsg(msg || "导出失败！");
        }
      });
    } catch (e: any) {
      console.error(e);
      errFunc && errFunc(e);
      MsgUtils.errorMsg("导出失败！");
    } finally {

    }
    return false;
  }
  return true;
}


declare type MessageType = 'success' | 'warning' | 'info' | 'error';

export interface RequestOptions {
  isLoading?: boolean;// 是否需要全局的加载效果
  successMsgShow?: boolean;// 是否需要提示success msg，设置为true，则需要设置successMsgText 字段，false,则需要自己处理
  successMsgText?: string;// success 提示的text,
  errorMsgShow?: boolean;// 是否需要提示error msg，设置为true，则需要设置 errorMsgText 字段，false,则需要自己处理
  errorMsgText?: string;// error 提示的text
}


/**
 * GET 请求默认设置
 */
export const GetRequestOptions: RequestOptions = {
  isLoading: true,
  successMsgShow: false
}

/**
 * POST 请求默认设置
 */
export const PostRequestOptions: RequestOptions = {
  isLoading: true,
  successMsgShow: false
}


export class Request<R> {
  // @ts-ignore
  //执行的http请求方法
  private httpFunc = (): Promise<AxiosResponse<any>> => {
  };
  private confirmObj?: ConfirmObj;
  private startDoing = () => {
    //执行开始
  }
  private endDoing = () => {
    //执行结束
  }
  private successFunc = (result: R) => {
    //成功的回调
  };
  //失败的回调,默认未知错误
  private errorFunc = (msg: string) => {
    if (!msg) {
      msg = '系统错误';
    }
    //失败的回调
    MsgUtils.errorMsg(msg);
  };

  public setHttp<R>(doHttpFunc: () => any) {
    this.httpFunc = doHttpFunc;
    return this;
  }

  private isRequest: boolean = true;
  private isLoading: boolean = false;
  private ifMsg?: string = undefined;
  /**
   * 是否执行请求
   */
  public if(isRequest: boolean,ifMsg?:string) {
    this.isRequest = isRequest;
    this.ifMsg = ifMsg;
    return this;
  }

  public loading() {
    this.isLoading = true;
    return this;
  }

  public confirm(msg: string, title?: string) {
    this.confirmObj = new ConfirmObj();
    this.confirmObj.confirmMsg = msg;
    this.confirmObj.confirmTitle = title || '操作确认';
    this.confirmObj.confirmType = 'warning';
    return this;
  }

  public ifConfirm(condition:boolean,msg: string, title?: string) {
    if (condition){
      this.confirmObj = new ConfirmObj();
      this.confirmObj.confirmMsg = msg;
      this.confirmObj.confirmTitle = title || '操作确认';
      this.confirmObj.confirmType = 'warning';
    }
    return this;
  }

  //网络开始的时候执行
  public start(startDoing: () => any) {
    this.startDoing = startDoing;
    return this;
  }

  //网络结束的时候执行
  public end(endDoing: () => any) {
    this.endDoing = endDoing;
    return this;
  }


  public successMsg(msgText: (result: R) => string) {
    this.successFunc = (result: R) => {
      MsgUtils.successMsg(msgText(result))
    };
    return this;
  }

  public successMsgStr(msgText: string) {
    this.successFunc = () => {
      MsgUtils.successMsg(msgText)
    };
    return this;
  }

  public errorMsg(msg: string) {
    this.errorFunc = (msg: string) => {
      MsgUtils.errorMsg(msg)
    };
    return this;
  }

  public success(successFunc: (result: R) => any) {
    this.successFunc = successFunc;
    return this;
  }

  public error(errorFunc: (msg: string) => any) {
    this.errorFunc = errorFunc;
    return this;
  }

  public request() {
    this.excuteHttp().then().finally(() => {
      this.endDoing();
      MsgUtils.loading_close();
    })
  }

  //正常请求
  private async excuteHttp() {
    //判断是否执行请求的条件
    if (!this.isRequest) {
      if (ObjectUtils.isNotEmpty(this.ifMsg)) {
        MsgUtils.errorMsg(this.ifMsg!);
      }
      return;
    }
    //判断是否弹窗
    if (this.confirmObj && !await this.confirmObj.isConfirmed()) {
      return;
    }
    if (this.isLoading) {
      MsgUtils.loading();
    }
    this.startDoing();
    await this.httpFunc().then((value: any) => {
      if (value?.data?.code === 401 && !location.hash.includes('login')) {
        loginDue();
        return;
      }
      //成功或者失败的回调
      if (value?.data?.success === true) {
        this.successFunc(value?.data);
      } else {
        this.errorFunc(value?.data?.msg || (Utils.format('{}, {}: {}', i18n('系统错误'), i18n('错误码'), value?.request?.status)));
      }
    }).catch((reason: any) => {
      console.error(i18n('请求错误信息')+"：", reason);
    });
  }

  //下载文件
  public async download(fileName: string) {
    //判断是否弹窗
    if (this.confirmObj && !await this.confirmObj.isConfirmed()) {
      return;
    }

    if (this.isLoading) {
      MsgUtils.loading();
    }
    this.startDoing();
    await this.httpFunc().then(value => {
      if (!downloadErrorInfoHandle(value, this.errorFunc)) {
        return;
      }
      if (value?.data?.code === 401 && !location.hash.includes('login')) {
        loginDue();
        return;
      }
      if (value?.data?.success != undefined && value?.data?.success === false) {
        this.errorFunc(value.data.msg || i18n('文件下载错误!'));
        return;
      }
      this.result2File(value, fileName);
      this.successFunc(value.data);
    }).catch(reason => {
      console.error(reason)
      this.errorFunc(i18n('文件下载错误,请联系系统管理员!'));
    }).finally(() => {
      this.endDoing();
      MsgUtils.loading_close();
    });
  }

  //将返回的文件流转换为文件
  private result2File(value: any, fileName: string) {
    const blob = new Blob([value.data], {type: value.headers['content-type']});
    const downloadElement = document.createElement('a');
    const href = window.URL.createObjectURL(blob); //创建下载的链接
    downloadElement.href = href;
    downloadElement.download = fileName; //下载后文件名
    document.body.appendChild(downloadElement);
    downloadElement.click(); //点击下载
    document.body.removeChild(downloadElement); //下载完成移除元素
    window.URL.revokeObjectURL(href); //释放掉blob对象
  }
}


export default new Request<Result<any>>();


export class ConfirmObj {
  //确认弹窗的内容
  public confirmMsg!: string;
  //确认弹窗的标题,默认 操作确认
  public confirmTitle?: string;
  //确认弹窗的类型,默认 info
  public confirmType?: MessageType;

  public async isConfirmed() {
    let confirm = true;
    await ElMessageBox.confirm(this.confirmMsg, this.confirmTitle ? this.confirmTitle : '操作确认', {
      confirmButtonText: '确认', cancelButtonText: '取消', type: this.confirmType ? this.confirmType : 'info',
    }).then((res) => {
      confirm = (res === 'confirm');
    }).catch(reason => {
      confirm = false;
    });
    return confirm;
  }
}