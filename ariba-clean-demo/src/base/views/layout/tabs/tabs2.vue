<template>
  <div class="tabs">
    <el-icon class="collapseIcon" @click="stores().theme.isCollapse = !stores().theme.isCollapse">
      <i class="fa-solid fa-indent" v-if="stores().theme.isCollapse"></i>
      <i class="fa-solid fa-outdent" v-else></i>
    </el-icon>
    <el-tabs
        v-model="tabsKey"
        type="card"
        class="demo-tabs"
        @tabRemove="closeOne"
        @tab-change="tabChange"
    >
      <el-tab-pane
          :closable="item.id != homePageId"
          v-for="item in tabsMenus"
          :key="item.id"
          :label="i18n(item.name)"
          :name="item.id"
      >
      </el-tab-pane>
    </el-tabs>
    <div class="">
      <el-dropdown>
        <el-button class="tabs-dropdown-btn" link>
          {{i18n('更多')}}
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="closeOthers">{{i18n('关闭其他标签')}}
            </el-dropdown-item>
            <el-dropdown-item @click="closeAll">{{i18n('关闭所有标签')}}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
import {useRoute} from "vue-router";
import {onMounted, ref, watch} from "vue";
import {stores} from "@/base/stores";
import {getMenuByPath} from "@/base/ts/service/AuthService.ts";
import router from "@/base/router";
import {i18n} from "@/base/ts/service/I18nService.ts";

const route = useRoute();
const tabsMenus = stores().user.tabsMenus || [];
//默认首页
const homePage = stores().cache.homePage;
const homePageId = stores().user.userInfo?.menuList?.filter(v => v.path === homePage).at(0)?.id;

const tabsKey = ref()

//路径变化,将当前路由对应的菜单添加到tabs中
watch(() => route.path, (newValue: any, oldValue: any) => {
  initTabs(newValue);
})

const initTabs = (path: string) => {
  const menu = getMenuByPath(path);
  if (menu) {
    if (tabsMenus.findIndex(item => item.id === menu.id) === -1) {
      //首页排第一
      if (menu.path === homePage) {
        tabsMenus.unshift(menu);
      } else {
        tabsMenus.push(menu);
      }
    }
    tabsKey.value = menu.id!;
  }
}

const tabChange = (menuId: string) => {
  const menu = tabsMenus.findLast(item => item.id === menuId);
  if (menu) {
    router.push(menu.path!);
  }
}

const closeAll = () => {
  tabsMenus.length = 0;
  router.push(homePage).then(v => initTabs(route.fullPath))

}

const closeOthers = () => {
  for (let i = tabsMenus.length - 1; i >= 0; i--) {
    if (tabsMenus[i].id !== tabsKey.value && tabsMenus[i].id !== homePageId) {
      tabsMenus.splice(i, 1);
    }
  }
}

const closeOne = (menuId: string) => {
  const index = tabsMenus.findIndex(item => item.id === menuId);
  tabsMenus.splice(index, 1);
  if (menuId === tabsKey.value) {
    //关闭的是当前标签,则切换到前一个tab
    router.push(tabsMenus[index - 1].path)
  }
}

onMounted(() => {
  initTabs(route.fullPath);
})

</script>

<style scoped lang="scss">
.tabs {
  height: var(--layout1-tabs-height);
  background-color: var(--layout1-tabs-bg);
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--Divider-color);
  box-sizing: border-box;

  .collapseIcon{
    margin: 0 10px;
    font-size: 18px;
  }

  .el-tabs {
    height: 100%;
    overflow: hidden;
    flex: 1;

    :deep(.el-tabs__header) {
      height: 100%;
      margin-bottom: 0;
      border: 0;

      .first-tab {
        background-color: red;
      }

      .el-tabs__item {
        height: var(--layout1-tabs-height);
        box-sizing: border-box;
        border: 0;
        border-right: 1px solid var(--Divider-color);

        &.is-active {
          border-bottom: 3px solid var(--primary);
        }
      }

      .el-tabs__nav-prev {
        height: 100%;
        display: flex;
        align-items: center;
        border-right: 1px solid var(--Divider-color);
      }

      .el-tabs__nav-next {
        height: 100%;
        display: flex;
        align-items: center;
        border-right: 1px solid var(--Divider-color);
        border-left: 1px solid var(--Divider-color);
      }

      .el-tabs__nav {
        height: 100%;
        border-radius: 0;
        border: 0;
      }

      .el-tabs__new-tab {
        display: none;
      }
    }
  }

  .tabs-dropdown-btn {
    margin-left: 15px;
    margin-right: 15px;
    outline: 0;
  }
}
</style>