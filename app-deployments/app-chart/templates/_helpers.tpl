{{/*
Expand the name of the chart.
*/}}
{{- define "app-chart.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "app-chart.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- $appEnv := default .Values.app.env .Values.envOverride }}
{{- $releaseName := default .Release.Name .Values.app.name }}
{{- if contains $name $releaseName }}
{{- $releaseName | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" $releaseName $appEnv |trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "app-chart.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "app-chart.labels" -}}
helm.sh/chart: {{ include "app-chart.chart" . }}
{{ include "app-chart.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "app-chart.selectorLabels" -}}
app.kubernetes.io/name: {{ include "app-chart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "app-chart.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "app-chart.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}


{{/*
拼接完整的镜像地址：registry/namespace/repository:tag
*/}}
{{- define "app-chart.image" -}}
{{- /* 拼接镜像前缀：registry/namespace/repository */}}
{{- $imagePrefix := printf "%s/%s/%s" .Values.image.registry .Values.image.namespace .Values.image.repository -}}
{{- $imageTag := .Values.image.tag -}}
{{- /* 拼接完整镜像地址 */}}
{{- printf "%s:%s" $imagePrefix $imageTag -}}
{{- end }}

{{/*
Name of the OPA bundle configuration ConfigMap.
*/}}
{{- define "app-chart.bundleConfigMapName" -}}
{{- printf "%s-bundle-config" ((include "app-chart.fullname" .) | trunc 49 | trimSuffix "-") }}
{{- end }}

{{/*
Name of the chart-managed GitLab bundle authentication Secret.
*/}}
{{- define "app-chart.bundleAuthSecretName" -}}
{{- printf "%s-bundle-auth" ((include "app-chart.fullname" .) | trunc 51 | trimSuffix "-") }}
{{- end }}

{{/*
Name of the chart-managed custom CA Secret.
*/}}
{{- define "app-chart.bundleCASecretName" -}}
{{- printf "%s-bundle-ca" ((include "app-chart.fullname" .) | trunc 53 | trimSuffix "-") }}
{{- end }}

{{/*
Validate values required by the bundle integration.
*/}}
{{- define "app-chart.validateBundle" -}}
{{- if .Values.bundle.enabled }}
  {{- $serviceURL := required "bundle.serviceUrl is required when bundle.enabled=true" .Values.bundle.serviceUrl }}
  {{- $resource := required "bundle.resource is required when bundle.enabled=true" .Values.bundle.resource }}
  {{- $_ := required "bundle.auth.username is required when bundle.enabled=true" .Values.bundle.auth.username }}
  {{- if not .Values.bundle.auth.existingSecret }}
    {{- $_ := required "bundle.auth.deployToken is required when bundle.enabled=true (or set bundle.auth.existingSecret to use a pre-created Secret)" .Values.bundle.auth.deployToken }}
  {{- end }}
  {{- if and .Values.bundle.auth.existingSecret .Values.bundle.auth.deployToken }}
    {{- fail "bundle.auth.existingSecret and bundle.auth.deployToken are mutually exclusive" }}
  {{- end }}
  {{- if not (regexMatch "^https://[^/[:space:]]+(/[^[:space:]]*)?$" $serviceURL) }}
    {{- fail "bundle.serviceUrl must be a valid HTTPS URL" }}
  {{- end }}
  {{- if eq (trimAll "/" $resource) "" }}
    {{- fail "bundle.resource must contain a filename or relative path" }}
  {{- end }}
  {{- if regexMatch "[[:space:]]" $resource }}
    {{- fail "bundle.resource must not contain whitespace" }}
  {{- end }}
  {{- $minDelay := int .Values.bundle.polling.minDelaySeconds }}
  {{- $maxDelay := int .Values.bundle.polling.maxDelaySeconds }}
  {{- if lt $minDelay 1 }}
    {{- fail "bundle.polling.minDelaySeconds must be greater than zero" }}
  {{- end }}
  {{- if lt $maxDelay 1 }}
    {{- fail "bundle.polling.maxDelaySeconds must be greater than zero" }}
  {{- end }}
  {{- if gt $minDelay $maxDelay }}
    {{- fail "bundle.polling.minDelaySeconds must not exceed maxDelaySeconds" }}
  {{- end }}
  {{- if and .Values.bundle.tls.caCert .Values.bundle.tls.existingSecret.name }}
    {{- fail "bundle.tls.caCert and bundle.tls.existingSecret.name are mutually exclusive" }}
  {{- end }}
  {{- if and .Values.bundle.tls.existingSecret.name (not .Values.bundle.tls.existingSecret.key) }}
    {{- fail "bundle.tls.existingSecret.key is required when an existing CA Secret is configured" }}
  {{- end }}
{{- end }}
{{- end }}
