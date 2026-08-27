# OPA Deployment

# OPA Service Helm 部署说明

## 1. 目标

在 Kubernetes 测试环境中，通过 Helm 部署一个独立的 OPA Service。

当前定位：

*   OPA 作为独立 Policy Decision Service
    
*   不使用 Sidecar
    
*   不接 Gatekeeper / Admission Webhook
    
*   不通过 Ingress / LoadBalancer 暴露
    
*   通过 ClusterIP 提供集群内部 HTTP API
    
*   后续可供 CSPM、Terraform CI、K8S Scanner 调用
    

架构：

```text
CSPM / Test Client
       |
       | HTTP :8181
       v
OPA ClusterIP Service
       |
       v
OPA Pod
       |
       v
/v1/data/...
```
---

## 2. 测试环境

当前测试环境：

```text
Kubernetes Context: kind-terraform-learn
Kubernetes Version: v1.35.0
Helm Version: v4.2.4
Namespace: policy-system
Helm Release: opa-test
Chart Name: opa-service

```
---

## 3. 创建 Namespace

```bash
kubectl create namespace policy-system

```

验证：

```bash
kubectl get namespace policy-system

```
---

## 4. 创建 Helm Chart

进入 Helm 目录：

```bash
cd /Users/Zhuanz/Documents/terraform-opa-demo/helm

```

创建：

```bash
helm create opa-service
cd opa-service

```

删除暂时不需要的模板：

```bash
rm templates/httproute.yaml
rm templates/ingress.yaml
rm templates/hpa.yaml
rm templates/NOTES.txt
rm -rf templates/tests

```

最终结构：

```text
opa-service/
├── Chart.yaml
├── values.yaml
├── .helmignore
└── templates/
    ├── _helpers.tpl
    ├── deployment.yaml
    ├── service.yaml
    └── serviceaccount.yaml

```
---

## 5. Chart.yaml

```yaml
apiVersion: v2

name: opa-service

description: OPA policy decision service for iVigil CSPM

type: application

version: 0.1.0

appVersion: "1.19.1"

```

注意：

*   `version`：Helm Chart 自身版本
    
*   `appVersion`：OPA 应用版本
    

---

## 6. values.yaml

```yaml
replicaCount: 1

image:
  repository: openpolicyagent/opa
  tag: "1.19.1"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8181

resources:
  requests:
    cpu: 100m
    memory: 128Mi
  limits:
    cpu: 500m
    memory: 512Mi

serviceAccount:
  create: true
  automount: false

```

测试阶段只启动一个 OPA Pod。

---

## 7. Deployment

`templates/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment

metadata:
  name: {{ include "opa-service.fullname" . }}

  labels:
    {{- include "opa-service.labels" . | nindent 4 }}

spec:
  replicas: {{ .Values.replicaCount }}

  selector:
    matchLabels:
      {{- include "opa-service.selectorLabels" . | nindent 6 }}

  template:
    metadata:
      labels:
        {{- include "opa-service.selectorLabels" . | nindent 8 }}

    spec:
      serviceAccountName: {{ include "opa-service.serviceAccountName" . }}

      containers:
        - name: opa

          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"

          imagePullPolicy: {{ .Values.image.pullPolicy }}

          args:
            - "run"
            - "--server"
            - "--addr=0.0.0.0:8181"

          ports:
            - name: http
              containerPort: 8181
              protocol: TCP

          resources:
            {{- toYaml .Values.resources | nindent 12 }}

```

OPA 实际启动命令：

```bash
opa run --server --addr=0.0.0.0:8181

```

这里的 `0.0.0.0` 只是 Pod 内监听，不代表公网暴露。

---

## 8. Service

`templates/service.yaml`

```yaml
apiVersion: v1
kind: Service

metadata:
  name: {{ include "opa-service.fullname" . }}

  labels:
    {{- include "opa-service.labels" . | nindent 4 }}

spec:
  type: {{ .Values.service.type }}

  selector:
    {{- include "opa-service.selectorLabels" . | nindent 4 }}

  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP

```

流量路径：

```text
Client
  |
  | :8181
  v
ClusterIP Service
  |
  | targetPort=http
  v
OPA Container :8181

```
---

## 9. ServiceAccount

`templates/serviceaccount.yaml`

```yaml
{{- if .Values.serviceAccount.create }}

apiVersion: v1
kind: ServiceAccount

metadata:
  name: {{ include "opa-service.serviceAccountName" . }}

  labels:
    {{- include "opa-service.labels" . | nindent 4 }}

automountServiceAccountToken: {{ .Values.serviceAccount.automount }}

{{- end }}

```

OPA 当前不需要访问 Kubernetes API，因此：

```yaml
automountServiceAccountToken: false

```

当前不需要：

```text
Role
RoleBinding
ClusterRole
ClusterRoleBinding

```
---

## 10. Helm 静态检查

```bash
helm lint .

```

渲染最终 YAML：

```bash
helm template opa-test .

```

这里：

```text
opa-test

```

是 Helm Release Name。

`helm template` 只生成 YAML，不真正部署。

---

## 11. 部署

```bash
helm install opa-test . \
  --namespace policy-system

```

部署成功：

```text
NAME: opa-test
NAMESPACE: policy-system
STATUS: deployed
REVISION: 1

```

查看 Helm Release：

```bash
helm list -n policy-system

```
---

## 12. 查看 Kubernetes 资源

```bash
kubectl get all -n policy-system

```

当前可以看到：

```text
Deployment
    |
    v
ReplicaSet
    |
    v
Pod

Service -> ClusterIP :8181

```

例如：

```text
pod/opa-test-opa-service-xxxxx

service/opa-test-opa-service
TYPE: ClusterIP
PORT: 8181

deployment.apps/opa-test-opa-service

```
---

## 13. 确认实际 OPA 镜像

```bash
kubectl get pod -n policy-system \
  -o jsonpath='{.items[0].spec.containers[0].image}{"\n"}'

```

预期：

```text
openpolicyagent/opa:1.19.1

```
---

## 14. 验证 OPA Health API

首先确认 Pod：

```bash
kubectl get pods -n policy-system

```

预期：

```text
1/1 Running

```

然后做 Port Forward：

```bash
kubectl port-forward \
  -n policy-system \
  service/opa-test-opa-service \
  8181:8181

```

新开终端：

```bash
curl http://127.0.0.1:8181/health

```

正常应该返回 HTTP 200，例如：

```json
{}

```

到这里说明：

```text
Helm
  ↓
Deployment
  ↓
ReplicaSet
  ↓
OPA Pod
  ↓
ClusterIP Service
  ↓
OPA HTTP API

```

整个链路正常。