import {BaseApi, BaseEntity, getApiUrl, Result} from "@/base/ts/api/base/BaseApi.ts";
import {Request} from "@/base/ts/api/base/Request.ts";
import http from "@/base/ts/api/base/axios.ts";

class SysDictItemApi<T>  {
    API_PREFIX = '/sys/dictItem';
    public getList(params?: any) {
        let url = getApiUrl(this.API_PREFIX, '/list');
        return new Request<Result<T[]>>().setHttp(() => http.get(url, {params: params}))
    }
}

export default new SysDictItemApi<DictItem>();

export interface DictItem extends BaseEntity {
    id?: string;
    label?: string;
    value?: string|boolean;
    code?: string;
    remark?: string;
    sort?: number;
    disabled?: boolean;
    isDefault?: boolean;
}
