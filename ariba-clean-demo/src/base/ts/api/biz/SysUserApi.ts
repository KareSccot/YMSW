import {BaseApi, BaseEntity} from "@/base/ts/api/base/BaseApi.ts";

class SysUserApi<T> extends BaseApi<T> {
    API_PREFIX = '/sys/user';

}

export default new SysUserApi<SysUser>();

export interface SysUser extends BaseEntity {
    //姓名
    name?: string;
    //账号
    account?: string;
    //手机号
    phone?: string;
    //邮箱
    email?: string;
    //状态
    status?: string;

    sara?: () => {
    }
}

export interface UserSelectorDto {
    size: number;
    //是否只查询有效的用户
    searchActive?: boolean;
    //查询的参数
    searchParam?: string;
    //要排除的用户ID
    excludeIds?: number[];
}
