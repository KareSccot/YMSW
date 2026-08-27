import {Request} from "@/base/ts/api/base/Request.ts";
import {BaseApi, BaseEntity, Result} from "@/base/ts/api/base/BaseApi.ts";
import http from "@/base/ts/api/base/axios.ts";

class SysPageApi<T> extends BaseApi<T> {
  API_PREFIX = '/sys/page';

  public getByCode(code: string) {
    return new Request<Result<T>>().setHttp(() => http.get(this.getUrl(`/${code}.json`)));
  }

}

export default new SysPageApi<SysPage>();

export interface SysPage extends BaseEntity {
  jsonCode?: string,
  pageCode?: string,
}
