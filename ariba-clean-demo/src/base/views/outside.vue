<template>
  <router-view :key="useRoute().fullPath"/>
</template>

<script setup lang="ts">
import {useRoute} from "vue-router";
</script>
<style lang="scss">
</style>
