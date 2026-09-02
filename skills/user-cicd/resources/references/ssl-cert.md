# SSL 证书在公司 CICD 体系里的角色

## 目录

- [A. 何时需要 SSL（决策树）](#a-何时需要-ssl决策树)
- [B. 证书从哪里来](#b-证书从哪里来)
- [C. PEM 格式细节](#c-pem-格式细节)
- [D. vm-deploy.yml 的固定路径行为](#d-vm-deployyml-的固定路径行为)
- [E. 容器里怎么用（compose volumes）](#e-容器里怎么用compose-volumes)
- [F. 文件权限陷阱与修复（PermissionError [Errno 13]）](#f-文件权限陷阱与修复permissionerror-errno-13)
- [G. 端口约定（host port vs container port）](#g-端口约定host-port-vs-container-port)
- [H. 不需要 SSL 的项目怎么办](#h-不需要-ssl-的项目怎么办)

> skill 怎么用这份目录：Step 2.A 第 10 题"应用是否监听 HTTPS"前 / Step 4 输出变量清单前先看 § A；用户说"证书哪来"就拿 § B；用户 docker-compose / Dockerfile 要挂载证书时看 § D + § E；**HTTPS 场景必读 § F（文件权限）+ § G（端口）**。

---

## A. 何时需要 SSL（决策树）

```
应用容器自己在 443 / 8443 / 其它 HTTPS 端口监听吗？
├─ 是 → 容器需要证书 → **配置 *_SSL_CERT 和 *_SSL_KEY（必填）**
│       典型场景：nginx 容器跑 SPA、Spring Boot 直接跑 HTTPS、FastAPI uvicorn 加 --ssl-keyfile
└─ 否 → 容器只跑 HTTP → **不需要配置，留空即可**
        典型场景：内网服务（HTTP 8080）+ 上游 LB / Ingress / 反向代理终止 SSL
        ↑ 多数业务后端走这条
```

**关键事实**：`vm-deploy.yml` 部署脚本会**无条件** scp `${*_SSL_CERT}` 和 `${*_SSL_KEY}` 到远端 `${DEPLOY_PATH}/server.crt` 和 `server.key`。如果 GitLab CI 变量没设值，scp 的是空文件——**不报错**。所以「不监听 HTTPS 的应用」留空这两个变量是安全的，不影响部署。

---

## B. 证书从哪里来

按优先级：

| 来源 | 适合 | 怎么拿 |
|---|---|---|
| **公司内部 CA** | UAT + PROD（推荐） | 找公司 InfoSec / IT 申请 `<your-domain>.wuxibiologics.com` 证书，会给 fullchain.pem 和 privkey.pem |
| **公司 Vault / Secret Manager** | 自动轮换的高合规场景 | 走团队 Vault 流程，由部署机定期拉新证书。本 skill 暂不覆盖此模式 |
| **Let's Encrypt** | 仅公网可达的服务（少见） | certbot 申请，3 个月续期一次 |
| **自签** | 仅 UAT 临时测试 | `openssl req -x509 -newkey rsa:4096 ...`；**不要用于 PROD** |

申请通常给两个文件：
- 证书（含 chain）：常见名 `fullchain.pem` / `<domain>.crt` / `<domain>.cer`
- 私钥：常见名 `privkey.pem` / `<domain>.key`

---

## C. PEM 格式细节

GitLab CI/CD Variable 里贴 PEM 文本，**整段含 BEGIN/END 行**：

```
-----BEGIN CERTIFICATE-----
MIIFxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
...（多行 base64）...
xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx==
-----END CERTIFICATE-----
```

私钥同理（`-----BEGIN PRIVATE KEY-----` 或 `-----BEGIN RSA PRIVATE KEY-----`）。

**踩坑点**：
- **整段都贴**，含 BEGIN/END 行（很多人只贴中间 base64，部署后 nginx 报 "PEM_read_bio_X509"）
- **若证书有 chain**（intermediate + root），全部追加在一份文件里（fullchain），不要分开
- **私钥不能有 passphrase**（部署脚本不交互式输密码；`openssl rsa -in privkey.pem -out privkey-nopass.pem` 去掉）
- **结尾留空行**（PEM 习惯，多数实现要求）

---

## D. `vm-deploy.yml` 的固定路径行为

部署脚本（`cicd-template/jobs/deploy/vm-deploy.yml` 的 `.deploy_container`）固化了以下流程：

```bash
# 1. 把 GitLab CI 变量内容写到 runner 上的临时文件
echo "${SSL_CERT}" > "$DEPLOY_SSL_CERT"   # 路径形如 /tmp/ssl_cert_<pipeline_id>
echo "${SSL_KEY}"  > "$DEPLOY_SSL_KEY"
chmod 600 ...

# 2. scp 到远端 VM 固定位置
scp ... "$DEPLOY_SSL_CERT" "${DEPLOY_TARGET}:${DEPLOY_PATH}/server.crt"
scp ... "$DEPLOY_SSL_KEY"  "${DEPLOY_TARGET}:${DEPLOY_PATH}/server.key"
```

**结论**：远端 VM 上**永远是** `${DEPLOY_PATH}/server.crt` 和 `${DEPLOY_PATH}/server.key`。你的 docker-compose.yml 里 mount 时用这两个固定文件名，不能改。

---

## E. 容器里怎么用（compose volumes）

如果应用容器需要这两个证书，在 `docker-compose.yml` 取消注释 volumes 段：

```yaml
services:
  my-app:
    image: ${IMAGE_REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}
    volumes:
      - ./server.crt:/etc/ssl/server.crt:ro
      - ./server.key:/etc/ssl/server.key:ro
    # ...
```

**注意**：左侧 `./server.crt` 是相对于 `${DEPLOY_PATH}` 的（部署脚本会 `cd ${DEPLOY_PATH}` 再 `docker compose up`），所以 `./server.crt` 实际指向 `${DEPLOY_PATH}/server.crt`，正好是部署脚本 scp 过去的位置。

### E.1 nginx 容器

`nginx.conf` 里 `ssl_certificate` 指向 mount 进来的路径：

```nginx
server {
    listen 443 ssl;
    ssl_certificate     /etc/ssl/server.crt;
    ssl_certificate_key /etc/ssl/server.key;
    ...
}
```

参见 PMS 的 `Dockerfile_frontend`：通过 `COPY ${BUILD_FOLDER}/docker-entrypoint.sh /docker-entrypoint.sh` 在容器启动时把 SSL 配置注入 nginx。

### E.2 后端容器直接监听 HTTPS

如 Spring Boot：

```yaml
environment:
  - SERVER_SSL_KEY_STORE_TYPE=PEM
  - SERVER_SSL_CERTIFICATE=/etc/ssl/server.crt
  - SERVER_SSL_CERTIFICATE_PRIVATE_KEY=/etc/ssl/server.key
```

如 uvicorn（FastAPI）：

```yaml
command: uvicorn app.main:app --host 0.0.0.0 --port 8000 --ssl-keyfile /etc/ssl/server.key --ssl-certfile /etc/ssl/server.crt
```

> ⚠️ 上面写 `--port 8000`（容器内端口），**不是 443**。容器内 appuser 没权限绑 <1024 的特权端口；443 由 docker daemon（root）在 host 端绑定，再映射到容器的 8000。详见 § G。

---

## F. 文件权限陷阱与修复（PermissionError [Errno 13]）

### 现象

容器启动报 `PermissionError: [Errno 13] Permission denied` 加载 `server.key`，反复重启失败。

### 根因

cicd-template 的 `vm-deploy.yml` 部署脚本流程：

1. 把 GitLab CI 变量 `${SSL_CERT}` / `${SSL_KEY}` 内容 `echo` 到 runner 上的临时文件
2. `chmod 600` 临时文件
3. `scp` 到远端 `${DEPLOY_PATH}/server.crt` 和 `server.key`

scp 默认以 ssh 用户身份在远端写文件。这里 ssh 用户是 deploy 用户（如 `appdeploy`），但**新文件继承的是 source 端 mode 600**，且属主在远端是 deploy 用户 —— 实际看到的是 `appdeploy:appdeploy 600`。

bind mount 进容器后，文件 mode 600 + 属主 uid 跟容器内 `appuser`（uid 1000）**不一致** → 容器内 appuser 读不了。

### 3 种修复方案

#### 方案 A：host 端一次性 chmod 640 + chgrp 1000

```bash
# 在 VM 上：
sudo chmod 640 ${DEPLOY_PATH}/server.crt ${DEPLOY_PATH}/server.key
sudo chgrp 1000 ${DEPLOY_PATH}/server.crt ${DEPLOY_PATH}/server.key
docker compose -f ${DEPLOY_PATH}/docker-compose.yml restart
```

⚠️ **每次 deploy 都会重新 scp 覆盖**，临时修复失效。不推荐。

#### 方案 B：业务项目 `.gitlab-ci.yml` 加 `after_script` 跑一次 chmod

```yaml
deploy-container-uat:
  after_script:
    - apk add --no-cache openssh-client
    - DEPLOY_KEY_PATH="/tmp/deploy_key_${CI_PIPELINE_ID}"
    - echo "${UAT_SSH_PRIVATE_KEY}" > "$DEPLOY_KEY_PATH"; chmod 600 "$DEPLOY_KEY_PATH"
    - ssh -i "$DEPLOY_KEY_PATH" -p "${UAT_SSH_PORT}" -o StrictHostKeyChecking=no
        "${UAT_SSH_USER}@${UAT_SSH_TARGET}"
        "sudo chmod 640 ${UAT_DEPLOY_PATH}/server.* && sudo chgrp 1000 ${UAT_DEPLOY_PATH}/server.*"
    - rm -f "$DEPLOY_KEY_PATH"
```

持久但需要在业务项目重复一次 SSH 凭证逻辑，相对脏。

#### 方案 C（推荐）：Dockerfile 用 entrypoint 启动时修

让容器以 **root 启动**，entrypoint 脚本修文件权限后 `exec gosu appuser` 切到非 root 跑实际服务。一次写到 Dockerfile 永久生效，每次 deploy 自动跑。

参考脚本：`resources/templates/entrypoint-ssl.sh.example`（debian 系用 `gosu`，alpine 系把 `gosu` 换成 `su-exec`）。

Dockerfile 末尾把：

```dockerfile
USER appuser
CMD ["python", "main.py"]
```

改成：

```dockerfile
# 不写 USER appuser —— 由 entrypoint 切换
RUN apt-get install -y gosu        # alpine: apk add su-exec
COPY entrypoint-ssl.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["python", "main.py"]          # 由 entrypoint 切到 appuser 后 exec 这条
```

`resources/templates/Dockerfile.python.example` 等模板已经预留了这段（默认注释，HTTPS 场景下取消注释）。

### Skill 在 Q10=B（HTTPS）时自动应用方案 C

`/cicd-init-repo` 生成 Dockerfile 时，如果 Q10 选 B，会自动取消注释这段、并把 `entrypoint-ssl.sh.example` 拷贝到项目根作 `entrypoint-ssl.sh`。

---

## G. 端口约定（host port vs container port）

容器化部署有两个端口，**功能完全不同**，不能混为一谈：

| 端口 | 谁绑定 | 范围 | 用户怎么填 |
|---|---|---|---|
| **host port** | docker daemon（以 root 跑） | 0-65535（可用特权 <1024） | `CUSTOM_HOST_PORT` 变量 |
| **container port** | 容器内应用进程（以 appuser 跑） | **≥1024**（非特权） | `CUSTOM_APP_PORT` 变量 |

`docker-compose.yml` 端口映射 `"host:container"`：

```yaml
ports:
  - "${CUSTOM_HOST_PORT:-8080}:${CUSTOM_APP_PORT:-8080}"
```

### HTTPS 标准配方

- `CUSTOM_HOST_PORT=443`（用户浏览器访问 `https://host/` 不用带端口号）
- `CUSTOM_APP_PORT=8000`（容器内 uvicorn / Spring Boot / nginx 监听 8000）
- 浏览器 → host:443 → docker daemon 转发 → 容器内 :8000

```yaml
# docker-compose.yml
ports:
  - "443:8000"
```

```bash
# uvicorn 容器内启动
uvicorn app.main:app --host 0.0.0.0 --port 8000 \
    --ssl-keyfile /etc/ssl/server.key --ssl-certfile /etc/ssl/server.crt
```

### 不要犯的错

❌ `CUSTOM_APP_PORT=443` —— 容器内 appuser 绑不了 443，启动报 `Permission denied`（不同于 § F 的 SSL 读权限错，但同样是非 root 用户特权操作）
❌ `ports: ["${CUSTOM_APP_PORT}:${CUSTOM_APP_PORT}"]` —— 把 host 和 container 端口绑死，无法分别控制
❌ 在容器里以 root 跑应用绕过此问题 —— 违反公司非 root 用户规范

---

## H. 不需要 SSL 的项目怎么办

直接**不配置** `*_SSL_CERT` 和 `*_SSL_KEY`（或留空字符串）。部署脚本会 scp 两个空文件到远端，容器忽略它们就行。

变量清单里这两项标"按需"而非"必填"。
