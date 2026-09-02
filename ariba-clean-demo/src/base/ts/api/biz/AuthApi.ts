import {getApiUrl, Result} from "@/base/ts/api/base/BaseApi.ts";
import http from "@/base/ts/api/base/axios.ts";
import {Request} from "@/base/ts/api/base/Request.ts";
import {SysUser} from "@/base/ts/api/biz/SysUserApi.ts";
import {SysMenu} from "@/base/ts/api/biz/SysMenuApi.ts";

class AuthApi {
    API_PREFIX = '/sys/auth';
    SAML_PREFIX = '/saml';

    login(loginVo: LoginVo) {
        let url = getApiUrl(this.API_PREFIX, '/login');
        return new Request<Result<LoginDto>>().setHttp(() => http.post(url, loginVo));
    }

    refreshUserInfo() {
        let url = getApiUrl(this.API_PREFIX, '/refreshUserInfo');
        return new Request<Result<LoginDto>>().setHttp(() => http.post(url));
    }

    samlLogin(relayState?: string): string {
        let url = getApiUrl(this.SAML_PREFIX, '/login');
        if (relayState) {
            url += `?RelayState=${encodeURIComponent(relayState)}`;
        }
        return url;
    }

    samlAcs(samlResponse: string, relayState?: string) {
        let url = getApiUrl(this.SAML_PREFIX, '/acs');
        const formData = new FormData();
        formData.append('SAMLResponse', samlResponse);
        if (relayState) {
            formData.append('RelayState', relayState);
        }
        return new Request<Result<SamlLoginDto>>().setHttp(() => http.post(url, formData));
    }

    getSamlConfig() {
        let url = getApiUrl(this.SAML_PREFIX, '/config');
        return new Request<Result<SamlConfigDto>>().setHttp(() => http.get(url));
    }
}

export default new AuthApi();

export interface LoginVo {
    //账号
    account?: string;
    //账号
    phone?: string;
    //登录方式,PASSWORD,SMSCODE
    loginType?: string;
    //密码
    password?: string;
    //短信验证码
    smsCode?: string;
    //图形验证码
    picCode?: string;
    //是否保持登录
    isKeep?: boolean;
}
export interface RegistVo {
    //账号
    account?: string;
    //密码
    password?: string;
    name?: string;
    phone?: string;
    email?: string;
}

export interface LoginDto {
    token?: string;
    sysUser?: SysUser;
    isAdmin?: boolean;
    menuTree?: SysMenu[];
    menuList?: SysMenu[];
    roleList?: any[];
}

export interface SamlLoginDto {
    token?: string;
    userId?: string;
    userName?: string;
    email?: string;
    relayState?: string;
    newUser?: boolean;
}

export interface SamlConfigDto {
    enabled?: boolean;
    ssoUrl?: string;
    entityId?: string;
}
