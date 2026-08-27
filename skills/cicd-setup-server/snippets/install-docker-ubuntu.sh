# Ubuntu / Debian 安装 Docker Engine + Compose plugin
# 参考 https://docs.docker.com/engine/install/ubuntu/

# 卸载老版本（如有）
sudo apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true

# 安装前置依赖
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg lsb-release

# 添加 Docker 官方 GPG key（如内网无法访问 docker.com，由运维提供镜像源后替换下面 URL）
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# 添加 apt 源
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 安装 docker engine + compose plugin
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 启动并设开机自启
sudo systemctl enable --now docker
