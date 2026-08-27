# 让 GitLab Runner 能 SSH 进本机：在 sshd_config 里加 AllowUsers <DEPLOY_USER>@<RUNNER_IP>
#
# 核心目的：runner 能以部署用户身份登录。其它 sshd hardening（禁 password、禁 root 等）
# 假设你公司基线已经配过，本脚本不重复处理。
#
# 替换 <DEPLOY_USER> 和 <RUNNER_IP>

# 1. 备份原文件
sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.$(date +%Y%m%d-%H%M%S)

# 2. 加 AllowUsers 行
#    - 已经有 AllowUsers 行：在末尾追加新条目
#    - 没有 AllowUsers 行：在文件末尾新增
if grep -q '^AllowUsers' /etc/ssh/sshd_config; then
  sudo sed -i '/^AllowUsers/ s/$/ <DEPLOY_USER>@<RUNNER_IP>/' /etc/ssh/sshd_config
  echo "已在现有 AllowUsers 行末尾追加 <DEPLOY_USER>@<RUNNER_IP>"
else
  echo 'AllowUsers <DEPLOY_USER>@<RUNNER_IP>' | sudo tee -a /etc/ssh/sshd_config
  echo "已新增 AllowUsers 行"
fi

# 3. 验证语法 + reload（不要 restart，避免断当前 ssh 会话）
sudo sshd -t && echo "sshd config syntax OK"
sudo systemctl reload sshd || sudo systemctl reload ssh

# 4. 验证：从 runner IP 用部署 key ssh 测试一次
#    在 runner（或本地代替）：ssh -i <key> <DEPLOY_USER>@<本机 IP> 'whoami'
#    成功输出 <DEPLOY_USER> 即 OK
#
# 多台 runner：重复以上 sed/append 即可，AllowUsers 同行用空格分隔多个 user@ip
