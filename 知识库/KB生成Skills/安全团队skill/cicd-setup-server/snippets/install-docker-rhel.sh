# RHEL / CentOS / Rocky 安装 Docker Engine + Compose plugin
# 参考 https://docs.docker.com/engine/install/rhel/

# 卸载老版本（如有）
sudo yum remove -y docker docker-client docker-client-latest docker-common \
                   docker-latest docker-latest-logrotate docker-logrotate docker-engine \
                   podman runc 2>/dev/null || true

# 加 Docker 官方 yum repo（如内网无法访问，由运维提供镜像源后替换下面 URL）
sudo yum install -y yum-utils
sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

# 安装 docker engine + compose plugin
sudo yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 启动并设开机自启
sudo systemctl enable --now docker
