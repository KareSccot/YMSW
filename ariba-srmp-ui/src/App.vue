<template>
  <div id="base-main-app" style="overflow: hidden;height: 100%" :style="appStyle">
    <router-view/>
  </div>
</template>

<script setup lang="ts">
import {useRoute} from "vue-router";
import {onMounted, ref, watch} from "vue";
import {getMenuByPath} from "@/base/ts/service/AuthService.ts";
import {i18n} from "@/base/ts/service/I18nService.ts";
import {stores} from "@/base/stores";
import {env} from "@/base/ts/utils/env.ts";
import {doAppOnmountListener, doPageListener} from "@/base/ts/listener/PageListener.ts";
import {doLangListener} from "@/base/ts/listener/LangListener.ts";
import {doDisplayTypeListener} from "@/base/ts/listener/DisplayTypeListener.ts";
import {Utils} from "@/base/ts/utils/Utils.ts";

const route = useRoute();


watch(() => route.fullPath, (newValue: any, oldValue: any) => {
  changeTitle(getMenuByPath(newValue)?.name)
  //页面变化监听器
  doPageListener(newValue, oldValue);
})

watch(() => stores().cache.lang, (newValue: any, oldValue: any) => {
  changeTitle(getMenuByPath(route.fullPath)?.name)
  //语言变化监听器
  doLangListener(newValue, oldValue);
})

watch(() => stores().theme.displayType, (newValue: any, oldValue: any) => {
  //页面显示分辨率改变监听器
  doDisplayTypeListener(newValue, oldValue);
})


//页面缩放比例
const appStyle = ref({}) //缩放样式
const updateScale = () => {
  //判断是否手机页面
  if (Utils.urlToObject(window.location.href).isMobile === 'true') {
    appStyle.value = {};
    return
  }
  const designWidth = stores().theme.designWidth // 设计图宽度
  const clientWidth = document.documentElement.clientWidth
  const scale = clientWidth / designWidth //缩放比例
  // @ts-ignore
  window.windowScale = scale;  //将缩放比例存放公区,用于amis的sdk获取使用
  const clientHeight = document.documentElement.clientHeight; // 页面原始高度

  //缩放样式
  appStyle.value = {
    transform: `scale(${scale})`,
    transformOrigin: '0 0',
    width: `${designWidth}px`,
    height: `${clientHeight / scale}px`,
    // overflow:"hidden"
  }
}


function changeTitle(title?: string) {
  const projectName = i18n(env('project_name'));
  title = i18n(title);
  document.title = title ? title : projectName!;
}

onMounted(() => {
  //系统启动成功监听器
  doAppOnmountListener()
  //页面缩放比例
  if (stores().theme.displayType == 'fixed') {
    updateScale()
    window.addEventListener('resize', updateScale)
  }
})

</script>
<style lang="scss">
</style>
