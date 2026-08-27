<template>
  <component :is="stores().noCache.layout?.index">
    <router-view v-slot="{ Component }">
      <keep-alive :include="keepaliveList">
        <component :is="wrap(useRoute().fullPath, Component)" :key="useRoute().fullPath"/>
      </keep-alive>
    </router-view>
  </component>
</template>

<script setup lang="ts">
import {useRoute} from "vue-router";
import {computed, h} from "vue";
import {stores} from "@/base/stores";

let keepaliveList = computed<string[]>(() => stores().user.tabsMenus?.map(v => v.path!)||[]);

//修改页面的name为path,动态组件
const wrapperMap = new Map()
const wrap = (name:any, component:any) => {
  let wrapper
  const wrapperName = name
  if (wrapperMap.has(wrapperName)) {
    wrapper = wrapperMap.get(wrapperName)
  } else {
    wrapper = {
      name: wrapperName,
      render() {
        return h(component)
      },
    }
    wrapperMap.set(wrapperName, wrapper)
  }
  return h(wrapper)
}
</script>

<style scoped lang="scss">

</style>