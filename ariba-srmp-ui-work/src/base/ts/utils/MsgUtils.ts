import {type Action, ElMessage, ElMessageBox} from "element-plus/es";
import {ElLoading, ElNotification} from 'element-plus'
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";
import {i18n} from "@/base/ts/service/I18nService.ts";

export class MsgUtils {

  public static msg(type: "error" | "success" | "warning" | "info", message: string) {
    ElMessage({message: i18n(message), type: type, showClose: true})
  }

  public static successMsg(message: string) {
    ElMessage({message: i18n(message), type: 'success', showClose: true})
  }

  public static warningMsg(message: string) {
    ElMessage({message: i18n(message), type: 'warning', showClose: true})
  }

  public static infoMsg(message: string) {
    ElMessage({message: i18n(message), type: 'info', showClose: true})
  }

  public static errorMsg(message: string, duration?: number) {
    ElMessage({message: i18n(message), type: "error", showClose: true, duration: duration});
  }


  public static errorNotify(message: string, title?: string) {
    if (title == undefined) {
      title = i18n('发生错误');
    }
    ElNotification({message: i18n(message), title: title, type: "error", duration: 0, showClose: true})
  }

  public static confirm(doing: () => any, msg: string, title?: string, type?: 'success' | 'warning' | 'info' | 'error') {
    // @ts-ignore
    if (!(document.getElementsByClassName('el-message-box').length === 0 || document.getElementsByClassName('el-message-box__wrapper')?.item(0)?.style.display == "none")) {
      return;
    }
    let confirm = true;
    ElMessageBox.confirm(i18n(msg), title ? i18n(title) : i18n('操作确认'), {
      confirmButtonText: i18n('确认'), cancelButtonText: i18n('取消'), type: type ? type : 'warning',
    }).then((res) => {
      if ((res === 'confirm')) {
        doing();
      }
    }).catch(reason => {
    });
  }

  public static async confirmAmis(msg: string) {
    // @ts-ignore
    if (!(document.getElementsByClassName('el-message-box').length === 0 || document.getElementsByClassName('el-message-box__wrapper')?.item(0)?.style.display == "none")) {
      return false;
    }
    let confirm = '';
    await ElMessageBox.confirm(i18n(msg), i18n('操作确认'), {
      confirmButtonText: i18n('确认'),
      cancelButtonText: i18n('取消'),
      type: 'warning',
    }).then(value => {
      confirm = value;
    }).catch(reason => {
      confirm = reason;
    })
    return confirm === 'confirm';
  }

  public static alert(doing: () => any, msg: string, title?: string, type?: 'success' | 'warning' | 'info' | 'error') {
    // @ts-ignore
    if (!(document.getElementsByClassName('el-message-box').length === 0 || document.getElementsByClassName('el-message-box__wrapper')?.item(0)?.style.display == "none")) {
      return;
    }
    let confirm = true;
    ElMessageBox.alert(i18n(msg), title ? i18n(title) : i18n('操作确认'), {
      confirmButtonText: i18n('确认'), cancelButtonText: i18n('取消'), type: type ? type : 'warning',
      callback: (value, action: Action) => {
        doing();
      },
    }).then((res) => {
      if ((res === 'confirm')) {
        doing();
      }
    }).catch(reason => {
    });
  }


  public static confirmInput(doing: (value: any) => any, msg: string, title: string, inputValidator?: (value: any) => any) {
    ElMessageBox.prompt(msg, title ? title : undefined, {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputValidator: inputValidator,
    }).then(({value}) => {
      doing(value);
    }).catch(reason => {
    });
  }

  private static loadingInstance: any;

  public static loading() {
    if (ObjectUtils.isNotEmpty(this.loadingInstance)) {
      this.loadingInstance?.close()
      this.loadingInstance = undefined;
    }

    this.loadingInstance = ElLoading.service({
      lock: true,
      text: "",
      background: 'rgba(0, 0, 0, 0.1)',
    });
    setTimeout(() => {
      this.loadingInstance?.close()
    }, 300000)
  }

  public static loading_close() {
    this.loadingInstance?.close();
  }
}