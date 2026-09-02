import {BaseApi, type BaseEntity, type Result} from "@/base/ts/api/base/BaseApi.ts";
import {Request} from "@/base/ts/api/base/Request.ts";
import http from "@/base/ts/api/base/axios.ts";

class ProjectPageApi extends BaseApi<ProjectPage>{
  API_PREFIX = '/project/page';
  /**
   * 根据条件获取单条数据
   */
  public initSchema(t: ProjectPage) {
    return new Request<Result<ProjectPage>>().setHttp(() => http.get(this.getUrl('/getByCode'), {params: t}));
  }
  public saveSchema(queryParams: any,jsonCode:any,type:'save'|'release'|'markTag') {
    return new Request<Result<ProjectPage>>().setHttp(() => http.post(this.getUrl('/'+type), {...queryParams,jsonCode:JSON.stringify(jsonCode)}));
  }

  public markTag(queryParams: any, jsonCode:any, tag:string) {
    return new Request<Result<ProjectPage>>().setHttp(() => http.post(this.getUrl('/markTag'), {...queryParams,tag: tag ,jsonCode:JSON.stringify(jsonCode)}));
  }
}


export default new ProjectPageApi();


export interface ProjectPage extends BaseEntity {
  //应用code
  projectCode?: string;
  //模块code
  moduleCode?: string;
  //页面code
  pageCode?: string;
  //环境code
  envCode?: string;
  //页面Json
  jsonCode?: string;
  //应用名称
  appName?: string;
  //模块名称
  moduleName?: string;
  //页面名称
  pageName?: string;
  //环境名称
  envName?: string;
  //标签
  tag?: string;
}
