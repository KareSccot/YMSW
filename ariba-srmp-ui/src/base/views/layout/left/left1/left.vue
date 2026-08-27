<template>
  <div class="left">
    <el-menu
        ref="menuRef"
        :default-active="route.fullPath"
        class="el-menu-list"
        :collapse="stores().theme.isCollapse"
        :unique-opened="true"
    >
      <!--多级菜单,自循环-->
      <MenuItem
          @itemClick="itemClick"
          v-for="item in menuTree"
          :key="item.id"
          :item="item"
      >
      </MenuItem>
    </el-menu>
  </div>
</template>
<script setup lang="ts">
import {stores} from "@/base/stores";
import MenuItem from "@/base/views/layout/left/left1/menuItem.vue";
import {useRoute} from "vue-router";
import {SysMenu} from "@/base/ts/api/biz/SysMenuApi.ts";
import router from "@/base/router";
import {computed, ref} from "vue";
import {MenuInstance} from "element-plus";

const menuRef = ref<MenuInstance|null>(null);
const menuTree = computed(()=> stores().user.userInfo?.menuTree);
// miya:20250906 menutree发生变化，没有刷新菜单的bug
// const menuTree = ref(stores().user.userInfo?.menuTree);
// miya:20250907 menutree发生变化，没有刷新菜单的bug
// const menuTree = ref(JSON.parse(localStorage.getItem('userStore')).userInfo?.menuTree);

const route = useRoute();

//外链
const itemClick = (item: SysMenu) => {
  console.log(menuTree.value)
  if (item.menuType=='url' && item.isNewTab) {
    menuRef.value?.updateActiveIndex(route.fullPath);
    window.open(item.shortPath);
  }else {
    router.push(item.path!);
  }
}

</script>

<style scoped lang="scss">
.left {
  background-color: var(--layout1-left-bg);
}
.el-menu {
  height: 100%;
  overflow-y: auto;
}
.el-menu:not(.el-menu--collapse) {
  width: var(--layout1-left-width);
}
</style>