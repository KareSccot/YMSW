import {BaseApi, BaseEntity} from "@/base/ts/api/base/BaseApi.ts";


class SysMenuApi<T> extends BaseApi<T> {
    API_PREFIX = '/sys/user';
}

export default new SysMenuApi<SysMenu>();

export interface SysMenu extends BaseEntity {
    name?: string;
    //
    path?: string;

    shortPath?: string;

    params?: [{key?:string,value?:any}?];
    //父菜单ID
    parentId?: number;

    //
    icon?: string;

    menuType?: string;

    sort?: number;

    children?: SysMenu[];

    parentIds?: number[];

    isHeaderActive?: boolean;

    isHide?: boolean;

    isNewTab?: boolean;

    externalUrl?: string;

    //是否公开
    isPublic?: boolean;

    isNavActive?: boolean;

    // roleMenuList?:SysRoleMenu[]
    //菜单的按钮
    menuBtnList?: any[];


}

