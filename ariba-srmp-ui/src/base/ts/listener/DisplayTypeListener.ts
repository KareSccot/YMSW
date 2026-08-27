
//监听执行器
let displayTypeListener: (newValue: any, oldValue: any) => void

//设置监听器
export const setDisplayTypeListener = (doAnything: (newValue: any, oldValue: any) => void) => {
  displayTypeListener = doAnything;
}

//执行监听器
export function doDisplayTypeListener(newValue: any, oldValue: any) {
  if (displayTypeListener) {
    displayTypeListener(newValue, oldValue);
  }else {
    //刷新页面
    window.location.reload();
  }
}
