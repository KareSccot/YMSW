<template>
  <div class="saml-callback-container">
    <div v-if="loading" class="loading">
      <div class="spinner"></div>
      <p>正在登录，请稍候...</p>
    </div>
    <div v-else-if="error" class="error">
      <p class="error-message">{{ error }}</p>
      <button @click="retry" class="retry-btn">重新登录</button>
    </div>
    <div v-else class="redirecting">
      <p>登录成功，正在跳转...</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { stores } from "@/base/stores";
import AuthApi from "@/base/ts/api/biz/AuthApi.ts";
import { loginEnd } from "@/base/ts/service/AuthService.ts";

const loading = ref(true);
const error = ref('');
const redirecting = ref(false);

onMounted(() => {
  handleCallback();
});

function parseHashQuery(): URLSearchParams | null {
  const hash = window.location.hash;
  const queryIndex = hash.indexOf('?');
  if (queryIndex > -1) {
    return new URLSearchParams(hash.substring(queryIndex + 1));
  }
  return null;
}

function handleCallback() {
  const params = parseHashQuery();
  if (!params) {
    error.value = '无效的回调地址';
    loading.value = false;
    return;
  }

  const errorParam = params.get('error');
  if (errorParam) {
    error.value = decodeURIComponent(errorParam);
    loading.value = false;
    return;
  }

  const token = params.get('token');
  if (!token) {
    error.value = '未收到登录凭证';
    loading.value = false;
    return;
  }

  stores().user.userInfo = { token };

  const redirect = params.get('redirect') || undefined;

  AuthApi.refreshUserInfo()
    .success(result => {
      const loginDto = result.data;
      if (!loginDto || !loginDto.token) {
        error.value = '登录验证失败';
        loading.value = false;
        return;
      }
      redirecting.value = true;
      loginEnd(loginDto, redirect);
    })
    .error(msg => {
      error.value = msg || '登录验证失败';
      loading.value = false;
    })
    .request();
}

function retry() {
  window.location.href = '/#/login';
}
</script>

<style scoped>
.saml-callback-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.loading, .error, .redirecting {
  text-align: center;
  padding: 40px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
}

.spinner {
  width: 50px;
  height: 50px;
  border: 4px solid #f3f3f3;
  border-top: 4px solid #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 20px;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.loading p, .redirecting p {
  color: #666;
  font-size: 16px;
  margin: 0;
}

.error-message {
  color: #e74c3c;
  font-size: 16px;
  margin: 0 0 20px;
}

.retry-btn {
  padding: 10px 30px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: transform 0.2s;
}

.retry-btn:hover {
  transform: translateY(-2px);
}
</style>
