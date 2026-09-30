# SkillHub Skills（清洗版）

从内部 SkillHub 下载的 25 个 skill 中，剔除 7 个含内部系统信息（内部域名/内网 IP）的 skill 后保留 18 个。

## 保留的 18 个

| Skill | 作者 | 版本 |
|-------|------|------|
| add-ad-sso | Yang Dong | 1.0.0 |
| adding-e2e-tests | awesome-cursor-skills community | 1.0.0 |
| agentic-tracing | GDT | 1.0.0 |
| brainstorming | obra/superpowers | 5.1.0 |
| canvas-design | Anthropic | 1.0.0 |
| docx | Anthropic | 1.0.0 |
| finishing-a-development-branch | obra/superpowers | 5.1.0 |
| git-commit | awesome-cursor-skills community | 1.0.0 |
| mcp-builder | Anthropic | 1.0.0 |
| pdf | Anthropic | 1.0.0 |
| pptx | Anthropic | 1.0.0 |
| skill-creator | Anthropic | 1.0.0 |
| subagent-driven-development | obra/superpowers | 5.1.0 |
| systematic-debugging | obra/superpowers | 5.1.0 |
| ui-design-refiner | Yang Dong | 0.1.0 |
| ui-semantic-class-names | Yang Dong | 0.1.0 |
| writing-plans | obra/superpowers | 5.1.0 |
| xlsx | Anthropic | 1.0.0 |

## 已剔除（含内部域名/内网 IP，不入库）

auto-cicd-setup, deployment-it-checklist, find-skillhub-skills, gdt-ui-design, product-design, raven-ui-design, web-app-boilerplate

## 目录结构

- `.claude/skills/<skill-name>/` — 每个 skill 独立文件夹（skill.json + SKILL.md 及引用资源）

## 来源

内部 SkillHub（2026-09-30 下载）。剔除规则见 README 上表；完整 25 个原始下载保留在本地 `internJ/skillhub/`（不入库）。
