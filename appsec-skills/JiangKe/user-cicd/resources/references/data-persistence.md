# 数据持久化：bind mount vs named volume

> 看 skill 怎么用这份目录：审查 `docker-compose.yml` 时（Step 3.3 第 4 条）或审查 `Dockerfile` 用户/uid 时（Step 3.2 第 6 条）触发了关于持久化的疑问时 Read 这份；用户问"数据丢了"、"deploy 后 admin 账号没了"、"sqlite 数据消失"等也 Read 这份。

## 目录

- [A. TL;DR](#a-tldr)
- [B. 为什么 named volume 在公司 CICD 里不行](#b-为什么-named-volume-在公司-cicd-里不行)
- [C. 正确做法：host bind mount + 固定 uid](#c-正确做法host-bind-mount--固定-uid)
- [D. 抢救已经在 named volume 里的数据](#d-抢救已经在-named-volume-里的数据)
- [E. 复用同一个 mount（不要乱加 mount 点）](#e-复用同一个-mount不要乱加-mount-点)
- [F. PROD 长期方案](#f-prod-长期方案)
- [G. 常见症状速查](#g-常见症状速查)

---

## A. TL;DR

| 选择 | 在公司 CICD 体系下 | 备注 |
|---|---|---|
| docker named volume（`xguard-data:/app/data` + 顶层 `volumes: xguard-data:`） | ❌ **数据会丢** | 部署脚本 `down -v` 会清掉 |
| host bind mount 相对路径（`./data:/app/data`） | ✅ 推荐 | 跟随 `$DEPLOY_PATH`，简洁。前提：部署脚本不清空 `$DEPLOY_PATH` |
| host bind mount 绝对路径（`/home/appdeploy/<svc>-data:/app/data` 或 `${CUSTOM_DATA_DIR}:/app/data`） | ✅ 最防御性 | 路径在 `$DEPLOY_PATH` 之外，部署脚本碰都碰不到 |

**所有 bind mount 都需要 Dockerfile 里 `useradd --uid 1000 appuser`，并在 VM 端一次性 `chown 1000:1000 <host目录>`**。

---

## B. 为什么 named volume 在公司 CICD 里不行

公司 cicd-template 的部署脚本在 redeploy 时执行（典型流程）：

```bash
cd $DEPLOY_PATH
docker compose down -v --remove-orphans   # ← -v 是罪魁
docker compose pull
docker compose up -d
```

**`docker compose down -v`** 的 `-v` 意思是「连同 named volume 一起删」—— docker 把 compose 引用的所有 named volume 全清掉。这个行为对**无状态服务**很合理（每次部署都给一个干净环境，没有遗留状态），对**带 SQLite / 上传文件 / cache 的服务**是灾难。

业务项目不能改 cicd-template 部署脚本（那是平台基础设施）。**唯一选择**：让数据不放在 docker volume 里，而放在 host filesystem 上 —— 这就是 bind mount。

`docker compose down -v` **不会**触碰 host filesystem 上的目录（这超出了 docker 的管辖范围），所以 bind mount 安全。

---

## C. 正确做法：host bind mount + 固定 uid

三件事一起做才完整：

### 1. docker-compose.yml — 用 bind mount

```yaml
services:
  myapp:
    image: ${IMAGE_REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}
    volumes:
      - ./data:/app/data       # 默认走 $DEPLOY_PATH/data
      # 或 - ${CUSTOM_DATA_DIR:-/home/appdeploy/myapp-data}:/app/data
    restart: unless-stopped
# 注意：不要加顶层 volumes: 段
```

### 2. Dockerfile — 固定 uid

```dockerfile
RUN useradd --create-home --uid 1000 appuser \
    && chown -R appuser:appuser /app
USER appuser
```

**为什么必须 `--uid 1000`**：bind mount 后容器内 `/app/data` 的 owner 等于 host 上那个目录的 owner（一般是 host 上的 uid:gid）。如果容器内 appuser 的 uid 跟 host 目录的 uid 不一致，appuser 写不进去。固定 uid=1000 是约定（让所有项目运维步骤统一）。

如果不固定，每次 docker build 时 useradd 会拿"image 里 next available uid"（通常 1000，但有可能 999 或更高），运维端 `chown 1000:1000` 之后下次 rebuild 可能就失效。

### 3. VM 端 — 一次性 chown

每台部署 VM（UAT / PROD 各一次）：

```bash
sudo mkdir -p $DEPLOY_PATH/data           # 比如 /home/appdeploy/myapp/data
sudo chown 1000:1000 $DEPLOY_PATH/data
sudo chmod 755 $DEPLOY_PATH/data
```

漏了 → 容器启动报 `unable to open database file` 或 `Permission denied: '/app/data/xxx.db'`。

---

## D. 抢救已经在 named volume 里的数据

如果项目已经跑了一段时间，老数据在 named volume 里，**切换到 bind mount 之前**先把数据搬出来：

```bash
# 1. 在 VM 上看 volume 还在不在
docker volume ls | grep <svc>-data

# 2. 把 volume 内容拷到新的 host 目录（保留权限 + 切到 uid 1000）
sudo mkdir -p $DEPLOY_PATH/data
docker run --rm \
  -v <compose_project>_<svc>-data:/from:ro \
  -v $DEPLOY_PATH/data:/to \
  alpine sh -c "cp -av /from/. /to/ && chown -R 1000:1000 /to"

# 3. 确认
sudo ls -la $DEPLOY_PATH/data/

# 4. 改 docker-compose.yml + 重新部署
# 5. 老 volume 确认没用后再删（不要急着删，留几次 deploy 验证数据正确）
# docker volume rm <compose_project>_<svc>-data
```

注意 named volume 的实际名字一般是 `<compose project name>_<volume key>`，compose project 默认取 docker-compose.yml 所在目录的 basename。

---

## E. 复用同一个 mount（不要乱加 mount 点）

如果服务还有别的需要持久化的目录（skill workspace、上传文件、cache 等），**不要新加 mount 点**，而是落在已有 `/app/data` 的子目录下：

```yaml
volumes:
  - ./data:/app/data    # 一个 mount 搞定所有
```

```dockerfile
# 在 Dockerfile 里设环境变量指向子目录
ENV SKILL_WORKSPACE_ROOT=/app/data/workspaces \
    UPLOAD_DIR=/app/data/uploads
```

**为什么不加新 mount 点**：
- 每加一个 mount 都要 VM 端再 `mkdir + chown`，运维负担线性增长
- 多个 mount 点之间 atomic backup / restore 难做
- 配置文件冗余

子目录由应用代码用 `mkdir(parents=True, exist_ok=True)` 之类的 idempotent 调用按需创建，只要父目录（`/app/data`）的 owner 对了，所有子目录都对。

---

## F. PROD 长期方案

SQLite + bind mount 是 **UAT 适用的临时方案**。PROD 强烈建议：

- **外部 PostgreSQL**（公司 DB 团队 managed instance，或自建 RDS）—— SQLite 在多副本 / HA 场景下写锁冲突，根本不能跑
- **对象存储**（minio / 公司 OSS）放上传文件、报告产物 —— bind mount 的容量、备份、跨 VM 共享都是麻烦
- **bind mount 仅保留** 给真正"必须 local"的数据（不跨副本的运行时 cache 等）

Phase 2 切换时，把 `DATABASE_URL` / 存储 URL 通过 `CUSTOM_*` 变量切到外部资源，应用代码改一个 connection string 即可（前提是代码用 ORM / 抽象存储接口，没硬编码 SQLite）。

---

## G. 常见症状速查

| 症状 | 大概率原因 |
|---|---|
| 每次 deploy 后 admin 账号 / 业务数据全没了 | named volume 被 `down -v` 清了 → 切 bind mount |
| 容器启动报 `unable to open database file` / `sqlite3.OperationalError` | bind mount 目录权限不对 → `chown 1000:1000 $DEPLOY_PATH/data` |
| Permission denied 写 `/app/data/xxx` | 同上 + Dockerfile 里 useradd 没指定 uid → 改成 `--uid 1000` |
| `docker compose up` 后容器内 `/app/data/` 是空的，但 host 目录有文件 | host 目录路径错（compose 相对路径不是相对 `$DEPLOY_PATH` 而是相对 docker-compose.yml 所在目录），核对实际路径 |
| 第一次 deploy 时 host 目录自动被建出来但 owner 是 root | docker daemon 以 root 跑，bind mount 时如果 host 目录不存在它会建一个 root:root 的 → 必须 deploy 前手动 `mkdir + chown` |
