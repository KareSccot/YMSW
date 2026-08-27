import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";

export class Utils {
    //字符串格式化,将 format('xxxx{}xx{}xx','A','B')  = xxxxAxxBxx
    public static format(str: string, ...args: any[]): string {
        if (args == null || args.length === 0) {
            return str;
        }
        args.forEach(arg => {
            str = str.replace('{}', ObjectUtils.isEmpty(arg)?'':arg);
        })
        return str;
    }

    //复制文本到剪切板
    public static toClipboard(txt: string) {
        const handleCopy = (e: ClipboardEvent) => {
            // clipboardData 可能是 null
            e.clipboardData && e.clipboardData.setData('text/plain', txt);
            e.preventDefault();
            // removeEventListener 要传入第二个参数
            document.removeEventListener('copy', handleCopy);
        };
        document.addEventListener('copy', handleCopy);
        document.execCommand('copy');
    }

    public static urlToObject(url: string) {
        const search = url.split('?')[1];
        if (!search) {
            return {};
        }
        return Object.fromEntries(search.split('&').map(item => item.split('=')));
    }

    //将对象组装为url参数
    public static objectToUrlParams(obj: any) {
        let str = '';
        for (let key in obj) {
            if (obj.hasOwnProperty(key)) {
                str += key + '=' + obj[key] + '&';
            }
        }
        return str;
    }

    //下载文件
    public static downloadFile(url: string, fileName: string) {
        const a = document.createElement('a')
        a.href = url
        a.download = fileName // 下载后文件名
        a.style.display = 'none'
        document.body.appendChild(a)
        a.click() // 点击下载
        document.body.removeChild(a) // 下载完成移除元素
    }

    //删除数组的元素
    public static arrayDelete(array: any[], o: any) {
        let index = array.indexOf(o);
        if (index < 0) {
            return;
        } else {
            array.splice(index, 1);
        }
    }

    //判断是否是数字类型
    public static isNumber(value: any) {
        return !isNaN(parseFloat(value)) && isFinite(value);
    }

    /**
     * 数组根据对象的某个属性去重
     * @param arr
     * @param u_key
     */
    public static unique(arr: any[], u_key: string) {
        let map = new Map();
        arr.forEach((item, index) => {
            if (!map.has(item[u_key])) {
                map.set(item[u_key], item)
            }
        })
        return [...map.values()]
    }

    public static parseInt(str:any, def:any) {
        if (ObjectUtils.isEmpty(str)) {
            return def;
        }
        const number = parseInt(str);
        if (Number.isNaN(number)) {
            return def;
        } else {
            return Number(number);
        }
    }


    /**
     * 模板填充
     * @param template 模板 ${title.a}-[${title.b}]
     * @param obj 内容
     */
    public static template(template:string, obj:any) {
        return template.replace(/\$\{(\w+(\.\w+)*)\}/g, (match, p1) => {
            return p1.split('.').reduce((o:any, key:any) => (o ? o[key] : undefined), obj);
        });
    }
}
