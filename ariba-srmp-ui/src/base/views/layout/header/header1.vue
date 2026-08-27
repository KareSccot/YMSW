<template>
  <div class="header">
    <div class="header-left">
      <div class="header-logo">
        <img src="/icon/logo.svg" alt="药明生物">
      </div>
      <!--<horizontal-expand><span :class="['title',{'isCollapse': stores().theme.isCollapse}]"
                               v-show="!stores().theme.isCollapse">{{ i18n(env('company_name')) }}</span>
      </horizontal-expand>-->
      <el-icon @click="stores().theme.isCollapse = !stores().theme.isCollapse">
        <i class="fa-solid fa-indent" v-if="stores().theme.isCollapse"></i>
        <i class="fa-solid fa-outdent" v-else></i>
      </el-icon>
      <span class="project-name">{{ i18n(env('project_name')) }}</span>
    </div>
    <div class="header-middle">
      <component v-if="stores().noCache.layout.customerSolt.header" :is="stores().noCache.layout.customerSolt.header"/>
    </div>

    <div class="header-right">
      <span class="user-name">{{ stores().user.userInfo?.sysUser?.name }}</span>
      <setting></setting>
    </div>
  </div>
</template>

<script setup lang="ts">

import {stores} from "@/base/stores";
import HorizontalExpand from "@/base/views/component/horizontal-expand.vue";
import Setting from "@/base/views/layout/setting/setting1.vue";
import {i18n} from "@/base/ts/service/I18nService.ts";
import {env} from "@/base/ts/utils/env.ts";
</script>

<style scoped lang="scss">
.header {
  display: flex;
  justify-content: space-between;
  height: var(--layout1-heder-height);
  background-color: var(--layout1-header-bg);
  align-items: center;

  .header-left {
    display: flex;
    align-items: center;

    .header-logo {
      width: 205px;
      display: flex;
      justify-content: center;
      align-items: center;
	font-size: 18px;
    	color: white;

      img {
        height: calc(var(--layout1-heder-height) * 0.6);
        width: auto;
      }
    }

    .title {
      width: 135px;
      color: white;
      font-size: 18px;
    }

    .title.isCollapse {
      color: transparent;
    }

    .el-icon {
      color: white;
    }
    .project-name{
      color: white;
      margin-left: 10px;
      font-size: 18px;
    }
  }

  .header-middle {
    flex: 1;
    height: var(--layout1-heder-height);
    margin: 0 10px;
  }

  .header-right {
    display: flex;
    align-items: center;
    padding-right: 20px;

    .user-name {
      color: white;
      font-size: 14px;
    }
  }
}
</style>