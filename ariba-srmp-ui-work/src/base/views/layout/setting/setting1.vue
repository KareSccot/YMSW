<template>
  <div class="header-settting">
    <el-tooltip :content="i18n('设置')" placement="bottom" effect="light">
      <el-icon @click="drawer = true">
        <i class="fa-solid fa-gear"></i>
      </el-icon>
    </el-tooltip>

  </div>
  <el-drawer
      class="layout1-setting-drawer"
      append-to-body
      destroy-on-close
      size="400px"
      v-model="drawer"
      :title="i18n('设置中心')"
      direction="rtl"
  >

    <AmisRender page-code="layout1-setting"
                page-name="个人设置中心"
                :edit_xy="[20, 60]"/>
  </el-drawer>
</template>

<script lang="ts" setup>
import {ref} from 'vue'
import AmisRender from "@/base/views/component/amisRender.vue";
import {i18n} from "@/base/ts/service/I18nService.ts";

const drawer = ref(false)
//顶部菜单是否显示


</script>

<style lang="scss" scoped>
.header-settting {
  display: flex;
  align-items: center;
  font-size: 16px;
  color: white;
  margin-left: 20px;
  cursor: pointer;
}

.setting-drawer {
  font-size: 14px;
  height: 100%;
  text-align: center;
}

</style>