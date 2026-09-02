import axios, {AxiosError, type AxiosResponse} from 'axios'
import {loginDue} from "@/base/ts/service/AuthService.ts";
import {MsgUtils} from "@/base/ts/utils/MsgUtils.ts";
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";
import {stores} from "@/base/stores";
import {i18n} from "@/base/ts/service/I18nService.ts";
import {doApiListener} from "@/base/ts/listener/ApiListener.ts";
import {doApiEndListener} from "@/base/ts/listener/ApiEndListener.ts";
// 创建axios
const http = axios.create({
    baseURL: '/'
});

// 添加请求拦截器
http.interceptors.request.use(function (config) {

    //非上传的场景,需要过滤空值
    if (config.url && !config.url.includes('sys/file/amisUpload')) {
        //过滤空值
        config.params = cleanObject(config.params);
        config.data = cleanObject(config.data);
    }
    //设置登录path
    config.headers['requesthref'] = window.location.href;
    //设置用户登录token
    const token = stores().user.userInfo?.token
    if (token) {
        // @ts-ignore
        config.headers.Authorization = 'Bearer ' + token
    }
    // 在发送请求之前做些什么
    //执行api监听
    doApiListener(config);
    return config;
}, function (error) {
    // 对请求错误做些什么

    //返回监听
    doApiEndListener(error.config,error)
    return Promise.reject(error);
});

// 添加响应拦截器
http.interceptors.response.use(function (response: AxiosResponse) {
    doApiEndListener(response.config)

    //下载文件
    if (response.data instanceof Blob) {
        result2File(response);
    }

    const res = response.data;
    if (res.code == 401) { //登录超时
        loginDue();
        return response;
    }

    //刷新token
    if(res.token && stores().user?.userInfo){
        stores().user.userInfo!.token = res.token;
    }

    return response;
}, function (error:AxiosError) {
    // 对响应错误做点什么
    //返回监听
    doApiEndListener(error.config,error)
    return Promise.reject(error);
});
export default http;

//将返回的文件流转换为文件
function result2File(response: AxiosResponse) {
    //转态不对或者不含文件名
    if (response.status != 200 || !response.headers['content-disposition']?.includes('fileName=')) {
        return new Response(response.data).text().then(value => {
            const errorData = JSON.parse(value);
            if (errorData?.msg) {
                MsgUtils.errorMsg(errorData?.msg);
            }
        })
    }
    const fileName = response.headers['content-disposition']
      .replaceAll('attachment;fileName=', '')
      .replaceAll('attachment;', '')
      .replaceAll('fileName=', '') || i18n('未知文件名');
    const blob = new Blob([response.data], {type: response.headers['content-type']});
    const downloadElement = document.createElement('a');
    const href = window.URL.createObjectURL(blob); //创建下载的链接
    downloadElement.href = href;
    downloadElement.download = decodeURIComponent(fileName); //下载后文件名
    document.body.appendChild(downloadElement);
    downloadElement.click(); //点击下载
    document.body.removeChild(downloadElement); //下载完成移除元素
    window.URL.revokeObjectURL(href); //释放掉blob对象
}


export const isFalsy = (value: any) => value === '' || value === null || value === undefined
//为空的对象清除掉
export const cleanObject = (o: any) => {
    if (ObjectUtils.isEmpty(o)) {
        return o;
    }
    if (o instanceof FormData) {
        return o
    }
    const object = JSON.parse(JSON.stringify(o));
    for (const key in object) {
        if (object[key] && typeof object[key] === 'object') {
            cleanObject(object[key]);
        } else {
            if (isFalsy(object[key])) {
                delete object[key]
            }
        }
    }
    return object
}