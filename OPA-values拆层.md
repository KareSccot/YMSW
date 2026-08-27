# OPA values 拆层方案

## 结构

```
app-chart/
├── templates/          # Helm 模板（复用现有模板或新增）
└── values.yaml        # 通用默认值

app-values/
├── opa/
│   ├── common.yaml    # 通用配置（镜像/端口/SA）
│   ├── dev.yaml       # 测试环境配置
│   └── prod.yaml      # 生产环境配置（预留）
```

## common.yaml（通用层）

```yaml
# OPA 通用配置
image:
  repository: openpolicyagent/opa
  tag: "1.19.1"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8181

serviceAccount:
  create: true
  automount: false
```

## dev.yaml（测试环境）

```yaml
# OPA 测试环境配置
replicaCount: 1

resources:
  requests:
    cpu: 100m
    memory: 128Mi
  limits:
    cpu: 500m
    memory: 512Mi
```

## prod.yaml（生产环境 - 预留）

```yaml
# OPA 生产环境配置（待确认）
replicaCount: 2  # 生产建议多副本

resources:
  requests:
    cpu: 200m
    memory: 256Mi
  limits:
    cpu: 1000m
    memory: 1Gi
```

## 与 #6 CD 模板的对齐

| 字段 | 模板名 | 说明 |
|------|--------|------|
| 通用镜像/端口 | app-values/opa/common.yaml | 复用 #6 common 模板结构 |
| 环境差异 | app-values/opa/dev.yaml | 复用 #6 env 模板结构 |
| 应用名称 | opa | 珂服务名作为目录名 |
| 应用类型 | 独立服务 | 非前端，无需 Ingress |