<template>
  <div class="iframe-wrapper">
    <iframe id="iframe"  :src="url" >
    </iframe>
  </div>
</template>

<script setup lang="ts">
import {ref} from "vue";
import {useRoute} from "vue-router";
const url = ref(useRoute().query.url + '');
</script>

<style scoped lang="scss">
.iframe-wrapper {
  width: 100%;
  height: 100%;
}

#iframe {
  width: calc(100% - 2px) !important;
  height: 100%;
  border-width: 0;
}

</style>