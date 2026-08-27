
//页面变化监听执行器
let pageListener: (newPath: any, oldPath: any) => void

//设置页面变化监听器
export const setPageListener = (doAnything: (newPath: any, oldPath: any) => void) => {
  pageListener = doAnything;
}

//执行页面变化监听器
export function doPageListener(newPath: any, oldPath: any) {
  if (pageListener) {
    pageListener(newPath, oldPath);
  }
}

//系统启动成功监听器
let appOnmountListener: () => void

//设置系统启动成功监听器
export const setAppOnmountListener = (doAnything: () => void) => {
  appOnmountListener = doAnything;
}
//执行系统启动成功监听器
export function doAppOnmountListener() {
  if (appOnmountListener) {
    appOnmountListener();
  }
}
