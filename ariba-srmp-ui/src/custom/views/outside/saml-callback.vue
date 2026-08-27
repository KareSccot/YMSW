<template>
  <div class="saml-callback-container">
    <div v-if="loading" class="loading">
      <div class="spinner"></div>
      <p>正在登录，请稍候...</p>
    </div>
    <div v-else-if="error" class="error">
      <p class="error-message">{{ error }}</p>
      <p class="error-hint">3 秒后自动跳转到登录页...</p>
      <button @click="retry" class="retry-btn">前往登录页</button>
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

/** sessionStorage 键名：与路由守卫 promise.ts 中的 SSO_ATTEMPTED_KEY 一致 */
const SSO_ATTEMPTED_KEY = 'sso_attempted';

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

/** SSO 失败后跳转到登录页（保留 sso_attempted 标记，避免守卫再次自动跳 SSO） */
function fallbackToLoginPage() {
  window.location.href = '/#/outside/login';
}

/** SSO 失败：显示错误信息，3 秒后自动跳转登录页 */
function showSsoError(msg: string) {
  error.value = msg;
  loading.value = false;
  setTimeout(fallbackToLoginPage, 3000);
}

function handleCallback() {
  const params = parseHashQuery();
  if (!params) {
    showSsoError('无效的回调地址');
    return;
  }

  const errorParam = params.get('error');
  if (errorParam) {
    showSsoError(decodeURIComponent(errorParam));
    return;
  }

  // BL-006 修复：token 在 URL fragment（#后），不发送到服务器日志/Referer
  // 读取后立即用 history.replaceState 清除 URL，防止浏览器历史记录泄露
  const token = params.get('token');
  if (!token) {
    showSsoError('未收到登录凭证');
    return;
  }

  const redirect = params.get('redirect') || undefined;

  // 立即清除 URL 中的 token，防止泄露到浏览器历史记录
  const cleanHash = window.location.hash.split('?')[0];
  history.replaceState(null, '', window.location.pathname + cleanHash);

  // SSO 成功：清除 sso_attempted 标记，下次会话过期可再次自动 SSO
  sessionStorage.removeItem(SSO_ATTEMPTED_KEY);

  // 拿到 token，存入 store
  stores().user.userInfo = { token };

  // 用 token 刷新完整用户信息
  AuthApi.refreshUserInfo()
    .success(refreshResult => {
      const loginDto = refreshResult.data;
      if (!loginDto || !loginDto.token) {
        showSsoError('登录验证失败');
        return;
      }
      redirecting.value = true;
      loginEnd(loginDto, redirect);
    })
    .error(msg => {
      showSsoError(msg || '登录验证失败');
    })
    .request();
}

function retry() {
  fallbackToLoginPage();
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
  margin: 0 0 8px;
}

.error-hint {
  color: #999;
  font-size: 13px;
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
