# 在 VM 上生成 CI 部署专用的 SSH keypair
# 与个人登录 key 分开，便于回收。

# 用 ed25519 算法（比 RSA 短、强、生成快）
ssh-keygen -t ed25519 \
  -C "gitlab-ci-deploy-$(hostname)-$(date +%Y%m%d)" \
  -f /tmp/gitlab_deploy \
  -N ""   # 空 passphrase（GitLab CI 不交互式输入密码）

# 检查权限
chmod 600 /tmp/gitlab_deploy
chmod 644 /tmp/gitlab_deploy.pub

# 展示公钥（这一段会贴到 authorized_keys）
echo "=== Public key (will be appended to authorized_keys) ==="
cat /tmp/gitlab_deploy.pub
echo

echo "=== Private key fingerprint ==="
ssh-keygen -lf /tmp/gitlab_deploy
