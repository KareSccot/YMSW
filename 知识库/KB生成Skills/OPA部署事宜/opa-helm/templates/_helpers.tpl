{{/*
Expand the name of the chart.
*/}}
{{- define "opa-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Name of the OPA bundle configuration ConfigMap.
*/}}
{{- define "opa-service.bundleConfigMapName" -}}
{{- printf "%s-bundle-config" ((include "opa-service.fullname" .) | trunc 49 | trimSuffix "-") }}
{{- end }}

{{/*
Name of the chart-managed GitLab bundle authentication Secret.
*/}}
{{- define "opa-service.bundleAuthSecretName" -}}
{{- printf "%s-bundle-auth" ((include "opa-service.fullname" .) | trunc 51 | trimSuffix "-") }}
{{- end }}

{{/*
Name of the chart-managed custom CA Secret.
*/}}
{{- define "opa-service.bundleCASecretName" -}}
{{- printf "%s-bundle-ca" ((include "opa-service.fullname" .) | trunc 53 | trimSuffix "-") }}
{{- end }}

{{/*
Validate values required by the bundle integration.
*/}}
{{- define "opa-service.validateBundle" -}}
{{- if .Values.bundle.enabled }}
  {{- $serviceURL := required "bundle.serviceUrl is required when bundle.enabled=true" .Values.bundle.serviceUrl }}
  {{- $resource := required "bundle.resource is required when bundle.enabled=true" .Values.bundle.resource }}
  {{- $_ := required "bundle.auth.username is required when bundle.enabled=true" .Values.bundle.auth.username }}
  {{- $_ := required "bundle.auth.deployToken is required when bundle.enabled=true" .Values.bundle.auth.deployToken }}
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

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "opa-service.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "opa-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "opa-service.labels" -}}
helm.sh/chart: {{ include "opa-service.chart" . }}
{{ include "opa-service.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "opa-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "opa-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "opa-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "opa-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
