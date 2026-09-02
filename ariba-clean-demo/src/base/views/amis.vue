<template>
  <div class="amis-page">
    <AmisRender
        :key="key"
        :pageCode="pageCode"
        :pageName="pageName"
    >
    </AmisRender>
  </div>
</template>

<script setup lang="ts">

import {useRoute} from "vue-router";
import AmisRender from "@/base/views/component/amisRender.vue";
import {getMenuByPath} from "@/base/ts/service/AuthService.ts";
import {env} from "@/base/ts/utils/env.ts";
import {ref, watch} from "vue";

const route = useRoute();
const key = ref(Date.now());

const pageCode: any = route.params.pageCode || route.meta.pageCode || '404';
const pageName: any = getMenuByPath(route.path)?.name || env("project_name");

watch(() => route.fullPath, () => {
  key.value = Date.now();
});
</script>

<style scoped lang="scss">
.amis-page {
  height: 100%;
}
</style>
