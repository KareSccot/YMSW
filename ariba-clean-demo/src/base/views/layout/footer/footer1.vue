<template>
  <div class="footer">
    {{env('project_footer_content')}}
  </div>
</template>
<script setup lang="ts">
import {env} from "@/base/ts/utils/env.ts";
</script>

<style scoped lang="scss">
.footer {
  width: 100%;
  height: var(--layout1-footer-height) ;
  background-color:  var(--layout1-footer-bg) ;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 12px;
  z-index: 10;
}
</style>