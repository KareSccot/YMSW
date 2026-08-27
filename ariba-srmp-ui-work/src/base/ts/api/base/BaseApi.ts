import http from "@/base/ts/api/base/axios.ts";
import {Request} from "@/base/ts/api/base/Request.ts";
import {env} from "@/base/ts/utils/env.ts";


export class BaseApi<T> {
  API_PREFIX!: string;

  public getOne(t: T) {
    return new Request<Result<T>>().setHttp(() => http.get(this.getUrl('/one'), {params: t}));
  }

  public getById(id: string) {
    return new Request<Result<T>>().setHttp(() => http.get(this.getUrl('/getById'), {params: {id: id}}));
  }

  public getList(params?: any) {
    return new Request<Result<T[]>>().setHttp(() => http.get(this.getUrl('/list'), {params: params}))
  }

  public getPage(params:any) {
    return new Request<Result<Page<T>>>().setHttp(() => http.get(this.getUrl('/page'), {params: params}))
  }

  /**
   * 修改或者新增单条数据
   * @param t
   */
  public save(t: T) {
    return new Request<Result<T>>().setHttp(() => http.post(this.getUrl('/save'), t)).loading()
  }


  /**
   * 修改或者新增多条数据
   * @param list
   */
  public saveBatch(list: T[]) {
    return new Request<Result<any>>().setHttp(() => http.post(this.getUrl('/saveBatch'), list)).loading()
  }


  /**
   * 根据ID删除单条数据
   * @param id
   */
  public deleteById(id: string) {
    return new Request<Result<any>>().setHttp(() => http.post(this.getUrl('/delete/' + id), null)).loading()
  }

  /**
   * 删除-ById-批量
   * @param list
   */
  public deleteByIds(ids: string[]) {
    return new Request<Result<any>>().setHttp(() => http.post(this.getUrl('/deleteByIds/' + ids.join(',')))).loading()
  }



  public getUrl(request?: string): string {
    return getApiUrl(this.API_PREFIX, request);
  }
}


export function getApiUrl(API_PREFIX: string, request?: string) {
  if (request) {
    return env('api_prefix') + API_PREFIX + request;
  } else {
    return env('api_prefix') + API_PREFIX;
  }
}

export default new BaseApi();

export interface Page<T> {
  size?: number;
  current?: number;
  records?: T[];
  total?: number;
}


export interface Result<T> {
  code?: number;
  success?: boolean;
  msg?: string;
  data?: T;
}


export interface BaseEntity {
  id?: string;
  createBy?: string;
  createAt?: Date;
  updateBy?: string;
  updateAt?: Date;
}


export interface BaseUser {
  id?: string;
  name?: string;
  account?: string;
}
