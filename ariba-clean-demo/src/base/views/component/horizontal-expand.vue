<script setup lang="ts">
</script>

<template>
  <transition name="horizontal-expand"><slot></slot></transition>
</template>

<style scoped lang="scss">
.horizontal-expand-enter-active {
  transition: width 0.4s ease,
  padding 0.4s ease,
  opacity 0.4s ease;
  overflow: hidden;
}

.horizontal-expand-enter-from {
  width: 0; /* 从0宽度开始 */
  max-width: 0;
  padding: 0;
  opacity: 0;
}

.horizontal-expand-enter-to {
  margin-left: -40px;
  width: 100%; /* 展开到目标宽度 */
  max-width: 100%;
  padding: 0 20px;
  opacity: 1;
  flex-shrink: 1; /* 禁止弹性收缩 */
}

/* 折叠动画（离场）- 与之前方案保持一致 */
.horizontal-expand-leave-active {
  transition: width 0.4s ease,
  padding 0.4s ease,
  opacity 0.4s linear;
  overflow: hidden;
}

.horizontal-expand-leave-from {
  margin-left: -40px;
  width: 100%; /* 从原始宽度开始折叠 */
  padding: 0 20px;
  opacity: 1;
}

.horizontal-expand-leave-to {
  width: 0;
  max-width: 0;
  padding: 0;
  opacity: 1;
}

.animated-box {
  display: inline-block; /* 必需：避免布局抖动 */
  background: #f0f9ff;
  border: 1px solid #bae0ff;
}
</style>