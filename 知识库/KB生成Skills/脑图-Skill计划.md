# Domain Skill 脑图

## ▶ 节点 1：知识库（生成能力）

### 🔹 数据获取
- SSH 认证：零 token，SSH key 引导
- 固定 3 仓库：`cicd-template` / `gitlab-management` / `team-cicd`（整仓克隆，含所有分支）
- 自定义仓库入口：SSH URL，零 token
- 分支选择：generator 阶段展示所有分支列表，用户交互式选择

### 🔹 内容处理（7 Phase 流水线）
- Phase 1｜读取 → Phase 2｜分类 → Phase 3｜脱敏 → Phase 4｜加代码示例 → Phase 5｜丰富 → Phase 6｜QA 检查 → Phase 7｜输出
- **🆕跨仓 include 关联**

### 🔹 输出格式
- ✅ AI 版（机器友好）：Frontmatter + 加粗字段标签 + chunk 自包含
- 🧑‍💻 人类版（旅程式）：5 文件结构，无 frontmatter，骨架叙事
- 📁 输出目录：`~/Desktop/kb-cloned/知识库-外部版-AI/` + `知识库-外部版-人类/`

### 🔹 更新机制
- 🔄 full 模式（首次/全量重建）+ ➕ update 模式（增量，commit_sha diff → 只重写变动文件）
- **🆕多分支增量检测**
- **🆕增量更新端到端实测**

### 🔹 上传分发（规划中）
- 🌐 生成后上传（方式待定）+ 📜 版本管理（方案候选）

---

## ▶ 节点 2：领域操作（Domain Operations）

### 🔹 角色操作清单

> 🔒 **入口预置角色视图**：开发者不做角色选择，skill 在分发时已按角色分离——开发者看到的只有开发者视角的内容，平台工程师看到全功能视图。（对应 V6 讨论"卸出去的时候就已分离完"）

#### 👤 开发者（User-facing，平台 curated）
面向外部用户的独立 skill，平台工程师对齐后 curated 给出，不暴露内部细节。

##### 📦 接入引导
##### 📦 CI/CD 接入（cicd-init-repo 使用）
##### 📦 只读消费 + 只读排查

#### 👤 平台工程师（内部，仅专属能力）
平台工程师侧仅保留专属于平台工程师的能力；开发者可自助使用的功能已迁移至开发者侧。

##### 📦 知识库操作模块
##### 📦 基础设施配置模块
  - 配置部署 VM（cicd-setup-server）+ cicd-init-repo 维护（更新模板、加检测项、改 ArgoCD 规则）
  - **🆕自动检测 — cicd-init-repo/SKILL.md 加「Step 1.5」章节，6 检测项**
  - **🆕前端 ArgoCD 路径规则 — SKILL.md 加章节，6 条**
  - **🆕标准 Dockerfile/nginx 模板 — templates/ 加**
  - **🆕CD values YAML 模板 — templates/ 加 + SKILL.md 加 CD 章节**
  - **🆕cicd-setup-server VM-only 定界**
##### 📦 安全校验 + 深度排障模块

### 🔹 cicd skill 三层模型（V7 模块化）

- **解耦原则**：每个子模块是个黑盒——输入进、输出出，互相不戳内部；改一个不牵连其他，能被任意 parent 复用。

- cicd skill 按**层数**模块化（非 skill 个数）。现有 cicd-init-repo 是 2 层（编排 + 实现），**缺中间能力层**。三层落地如下。

#### 🔹 第 1 层｜入口/调度层
- 判断用户意图（接入新项目 / 审查现有 pipeline / 只生成 Dockerfile）+ 前/中/后检查控制 + 调度第 2 层子模块
- `前置检查`：项目目录校验、现有文件盘点、语言推断（pom.xml / package.json / go.mod）→ 不够走 feat 分支（不卡死）
- `中置`：调度子模块执行
- `后置检查`：合规红线检查（6 个安全 job 未被禁用）、变量清单完整性、三件套文件完整性

#### 🔹 第 2 层｜能力层
- 把 SKILL.md 里的执行逻辑拆成独立模块，每个可被多个 parent 复用：
  - `gitlab-ci-gen`：按单/多服务 + 部署方式生成 .gitlab-ci.yml
  - `dockerfile-gen`：按语言 + 参数化/简单模式生成 Dockerfile
  - `compose-review`：审查/生成 docker-compose.yml + 校验 CUSTOM_ 约定
  - `variables-output`：从 compose 推导 GitLab CI/CD 变量清单
- 后续新 parent（如 cicd-audit / cicd-migrate）可直接复用这些子模块，不用重写，用户不感知。

#### 🔹 第 3 层｜实现/参考层
- 内部实现细节，用户不感知；子模块通过 !reference 或 Read 调用
- `references`：base-image-catalog（编译/运行时镜像清单）、cicd-template-jobs（默认 job 详解 + 合规红线）、data-persistence、multi-node-deploy、ssl-cert
- `checklists`：gitlab-variables（GitLab CI/CD Variables UI 变量清单）
- `templates`：标准 Dockerfile / nginx 模板 / CD values YAML 模板


---

