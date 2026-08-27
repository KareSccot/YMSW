import {createPinia} from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate';
import {cacheStore, noCacheStore, themeStore, userStore} from "@/base/stores/stores.ts";
const pinia = createPinia();
pinia.use(piniaPluginPersistedstate);
export default pinia

//所有store注册进这里
export const stores = () => ({
  user: userStore(),
  cache: cacheStore(),
  theme: themeStore(),
  noCache: noCacheStore(),
});


//初始化 store,目的是为了在系统启动的时候,将默认值刷进localstorage
setTimeout(()=>{
  Object.getOwnPropertyNames(stores()).forEach((storesKey: string) => {
    // @ts-ignore
    const store = stores()[storesKey];
    Object.getOwnPropertyNames(store.$state).forEach((storeKey: string) => {
      const value = store[storeKey];
      store[storeKey] = undefined;
      store[storeKey] = value;
    })
  })
})


