import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";

export class Dates {

  //时间格式化
  public static date_format(date: Date, format: string): string {
    if (ObjectUtils.isEmpty(date)) {
      return '';
    }
    if (typeof date === 'string') {
      date = new Date(date);
    }
    let o: any = {
      "M+": date.getMonth() + 1, //month
      "d+": date.getDate(), //day
      "h+": date.getHours(), //hour
      "m+": date.getMinutes(), //minute
      "s+": date.getSeconds(), //second
      "q+": Math.floor((date.getMonth() + 3) / 3), //quarter
      "S": date.getMilliseconds() //millisecond
    };
    if (/(y+)/.test(format)) format = format.replace(RegExp.$1,
      (date.getFullYear() + "").substr(4 - RegExp.$1.length));
    for (var k in o)
      if (new RegExp("(" + k + ")").test(format))
        format = format.replace(RegExp.$1,
          RegExp.$1.length == 1 ? o[k] :
            ("00" + o[k]).substr(("" + o[k]).length));
    return format;
  }


  //2个时间的间隔的天数
  public static date_space(startDate: Date, endDate: Date): number {
    return Math.abs(parseInt((endDate.getTime() - startDate.getTime() / 1000 / 60 / 60 / 24)+''));
  }
}
