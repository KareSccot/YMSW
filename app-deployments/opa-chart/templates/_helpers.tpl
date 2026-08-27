{{/*
Expand the name of the chart.
*/}}
{{- define "opa-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
Mirrors app-chart.fullname convention: <app.name>-<app.env>.
*/}}
{{- define "opa-service.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- $appEnv := default .Values.app.env .Values.envOverride }}
{{- $releaseName := default .Release.Name .Values.app.name }}
{{- if contains $name $releaseName }}
{{- $releaseName | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" $releaseName $appEnv | trunc 63 | trimSuffix "-" }}
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
Create the name of the service account to use.
*/}}
{{- define "opa-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "opa-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
OPA image: openpolicyagent/opa:<tag> (public image, no registry/namespace).
*/}}
{{- define "opa-service.image" -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end }}
