<template>
  <div class="layout1 layout-main">
    <component :is="stores().noCache.layout?.header" />
    <div class="content">
      <component :is="stores().noCache.layout?.left" />
      <div class="content-main">
        <component :is="stores().noCache.layout?.tabs" />
        <div class="main" v-loading="loading">
          <slot></slot>
        </div>
        <component :is="stores().noCache.layout?.footer" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

import { onMounted, nextTick, ref } from "vue";
import {stores} from "@/base/stores";

let loading = ref(false)
onMounted(() => {
  loading.value = true
  nextTick(() => {
    loading.value = false
  })
})

</script>

<style scoped lang="scss">
.layout1 {
  height: 100%;

  .content {
    height: calc(100% - var(--layout1-heder-height));
    display: flex;

    .content-main {
      width: 100%;
      overflow: auto;
      height: 100%;
      background-color: var(--layout1-main-bg);

      //内容高度 = 100% - 头部高度 - 底部高度 - 顶部内边距 - 底部内边距
      .main {
        padding: var(--layout1-main-padding-top) var(--layout1-main-padding-right) var(--layout1-main-padding-bottom) var(--layout1-main-padding-left);
        height: calc(100% - var(--layout1-footer-height) - var(--layout1-tabs-height) - var(--layout1-main-padding-top) - var(--layout1-main-padding-bottom));
      }
    }
  }
}
</style>