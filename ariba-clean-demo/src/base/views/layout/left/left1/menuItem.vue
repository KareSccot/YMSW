<template>
  <el-sub-menu v-if="item.children && item.children.length > 0"
               :index="item.id+''" :expand-close-icon="CaretBottom" :expand-open-icon="CaretTop">
    <template #title>
      <i :class="item.icon"></i>
      <span>{{ i18n(item.name) }}</span>
    </template>
    <menu-item
        v-for="item2 in item.children"
        :key="item2.id"
        :item="item2"
        @itemClick="(childItem) => emits('itemClick', childItem)"
    ></menu-item>
  </el-sub-menu>
  <el-menu-item
      v-else
      :disabled="ObjectUtils.isEmpty(item.path)"
      @click="itemClick(item)"
      :class="ObjectUtils.isEmpty(item.path)? 'menu_no_path':''"
      :index="item.path||item.id">
    <i :class="item.icon"></i>
    <template #title>{{ i18n(item.name) }}</template>
  </el-menu-item>
</template>

<script lang="ts" setup>
import {computed} from 'vue'
import {ObjectUtils} from "@/base/ts/utils/ObjectUtils.ts";
import {i18n} from "@/base/ts/service/I18nService.ts";
import {SysMenu} from "@/base/ts/api/biz/SysMenuApi.ts";
import {CaretBottom, CaretTop} from "@element-plus/icons-vue";
//父级props传输的内容解析
const props = defineProps(['item']);
//定义到item变量中
const item = computed(()=> props.item);

let emits = defineEmits(['itemClick']);
const itemClick = (item: SysMenu) => {
  emits('itemClick', item);
}
</script>

<script lang="ts">
export default {
  name: 'menuItem',
}
</script>
<style scoped>
</style>