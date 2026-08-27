<template>
  <div :id="amis_root_id" class="amis-root-div"></div>
  <div class="page-editor"
      v-if="env('VITE_NODE_ENV') === 'dev'"
       @click="gotoEditPage"
       :style="{ 'left': edit_xy[0] + 'px', 'bottom': edit_xy[1] + 'px'}">
    <el-tooltip :content="`点击编辑【${props.pageName}】页面`" placement="top" effect="light">
      <el-icon color="white">
        <i class="fa-solid fa-paper-plane"></i>
      </el-icon>
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">

import SysPageApi from "@/base/ts/api/biz/SysPageApi.ts";
import {buildAmis} from "@/base/ts/amis/AmisBuilder.ts";
import {onActivated, onMounted, onUnmounted, ref, watch} from "vue";
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";
import {stores} from "@/base/stores";
import {UUID} from "@/base/ts/utils/UUID.ts";
import {Utils} from "@/base/ts/utils/Utils.ts";
import {env} from "@/base/ts/utils/env.ts";

const amis_root_id = 'amis_root_id_' + UUID.guid();

const props = defineProps({
  // 页面编码
  pageCode: {
    type: String,
    default: '404'
  },
  // 页面名称
  pageName: {
    type: String,
    default: env('project_name')
  },
  // 编辑按钮位置
  edit_xy: {
    type: Array<number>,
    default: [20, 10]
  },

});

const needUpdate = ref(false);

let amisInstance: any = undefined;

watch(() => props.pageCode, (newValue: any, oldValue: any) => {
  viewAmisPage(newValue);
})

watch(() => stores().cache.lang, (newValue: any, oldValue: any) => {
  const root_element = document.getElementById(amis_root_id);
  if (root_element) {
    viewAmisPage(props.pageCode);
  } else {
    needUpdate.value = true;
  }
})

onActivated(() => {
  if (needUpdate.value) {
    viewAmisPage(props.pageCode);
  }
})

onMounted(() => {
  viewAmisPage(props.pageCode);
})

onUnmounted(() => {
  if (ObjectUtils.isNotEmpty(amisInstance)) {
    amisInstance.unmount();
    amisInstance = undefined;
  }
})

function viewAmisPage(pageCode: string) {
  if (!pageCode) {
    return
  }
  needUpdate.value = false;
  SysPageApi.getByCode(pageCode).success(result => {
    amisInstance = buildAmis(amis_root_id, JSON.parse(result.data?.jsonCode! || '{}'));
  }).error((error) => {
    viewAmis404Page();
  }).request();
}

function viewAmis404Page() {
  SysPageApi.getByCode('404').success(result => {
    amisInstance = buildAmis(amis_root_id, JSON.parse(result.data?.jsonCode! || '{}'));
  }).error((error) => {
  }).request();
}

const gotoEditPage = () => {
  const url = Utils.format('{}?projectCode={}&pageName={}&pageCode={}',
      env('amis_editor_url'),
      env('project_code'),
      props.pageName,
      props.pageCode
  );
  window.open(url);
}

</script>

<style scoped lang="scss">
.page-editor {
  width: 30px;
  height: 30px;
  border-radius: 30px;
  background: var(--colors-brand-5);
  position: absolute;
  z-index: 9999;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;

  .fa-solid {
    font-size: 14px;
  }

  a {
    margin-top: 3px;
    font-size: 18px;
  }
}
</style>
