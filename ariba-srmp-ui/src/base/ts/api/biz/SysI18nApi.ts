import {BaseApi, BaseEntity, Result} from "@/base/ts/api/base/BaseApi.ts";
import {Request} from "@/base/ts/api/base/Request.ts";
import http from "@/base/ts/api/base/axios.ts";

class SysI18nApi<T> extends BaseApi<T> {
    API_PREFIX = '/sys/i18n';

    public getAll() {
        return new Request<Result<Record<string, any>>>().setHttp(() => http.get(this.getUrl('/all')));
    }
}

export default new SysI18nApi<SysI18n>();

export interface SysI18n extends BaseEntity {
    lang: string;
    pack: string;
}
