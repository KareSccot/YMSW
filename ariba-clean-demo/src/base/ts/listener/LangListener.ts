
//监听执行器
let langListener: (newValue: any, oldValue: any) => void

//设置监听器
export const setLangListener = (doAnything: (newValue: any, oldValue: any) => void) => {
  langListener = doAnything;
}

//执行监听器
export function doLangListener(newValue: any, oldValue: any) {
  if (langListener) {
    langListener(newValue, oldValue);
  }
}
