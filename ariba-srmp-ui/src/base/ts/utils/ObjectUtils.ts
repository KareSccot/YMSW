export class ObjectUtils {
    //判断对象是否为空
    public static isEmpty(obj: any): boolean {
        if (obj === undefined || obj == null) {
            return true;
        }
        if (Array.isArray(obj)) {
            return obj.length === 0;
        }
        if (typeof obj === 'string') {
            return obj.length === 0;
        }
        return false;
    }
    //判断对象是否不为空
    public static isNotEmpty(obj:any) {
        return !this.isEmpty(obj);
    }
    //2个对象相等
    public static equals(a1: any, a2: any) {
        return JSON.stringify(a1) === JSON.stringify(a2)
    }

    //2个对象不相等
    public static notEquals(a1: any, a2: any) {
        return !this.equals(a1, a2);
    }

    public static copy<T>(data:T) :T{
        return JSON.parse(JSON.stringify(data));
    }
}
