import {SysUser} from "@/base/ts/api/biz/SysUserApi.ts";
import {stores} from "@/base/stores";
import {LoginDto} from "@/base/ts/api/biz/AuthApi.ts";
import router from "@/base/router";


export class UserUtils {



  public static getLoginDto(): LoginDto|undefined {
    return stores().user.userInfo;
  }

  public static getUser(): SysUser {
    return this.getLoginDto()?.sysUser||{};
  }

  public static getUserId() {
    return this.getUser()?.id;
  }

  public static getAccount() {
    return this.getUser()?.account;
  }

  public static isAdmin(): boolean {
    return stores().user.userInfo?.isAdmin||false;
  }

  public static getDisplayByUser(user: SysUser) {
    return `${user.name} (${user.account})`
  }

  public static getDisplay() {
    return `${this.getUser().name} (${this.getUser().account})`
  }

  public static hasRole(roleCode: string) {
    if (this.isAdmin()){
      return true
    }
    return this.getLoginDto()?.roleList?.some(v => v.code === roleCode);
  }

  public static getAllBtnsCodeList(): string[] {
    const menu: any = this.getLoginDto()?.menuList?.filter(v => v.path === router.currentRoute.value.fullPath).at(0) || {};
    const menuBtnList: any[] = menu.menuBtnList || [];
    return menuBtnList.map((btn: any) => btn.code);
  }

  //是否有权限
  public static hasPermission(permission: string | string[]) {
    if (UserUtils.isAdmin()) {
      return true;
    }
    let btnCodeList: string[] = this.getAllBtnsCodeList();
    let hasPermission: boolean = false;
    if (permission instanceof Array) {
      const btnPermissions: string[] = permission;
      hasPermission = btnPermissions.some(btnPermission => {
        return btnCodeList.includes(btnPermission);
      })
    } else {
      const btnPermission: string = permission as string;
      hasPermission = btnCodeList.includes(btnPermission) || false;
    }
    return hasPermission;
  }
}
