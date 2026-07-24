{{/*
Expand the name of the chart.
*/}}
{{- define "smithy.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this
(by the DNS naming spec).
*/}}
{{- define "smithy.fullname" -}}
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
{{- define "smithy.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "smithy.labels" -}}
helm.sh/chart: {{ include "smithy.chart" . }}
{{ include "smithy.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: smithy
{{- end }}

{{/*
Selector labels
*/}}
{{- define "smithy.selectorLabels" -}}
app.kubernetes.io/name: {{ include "smithy.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The name of the orchestrator ServiceAccount to use.
*/}}
{{- define "smithy.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "smithy.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
The name of the Secret the orchestrator reads env from. Points at the
user-supplied existingSecret when set, otherwise the chart-templated Secret.
*/}}
{{- define "smithy.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else }}
{{- printf "%s-secrets" (include "smithy.fullname" .) }}
{{- end }}
{{- end }}

{{/*
The name of the orchestrator ConfigMap.
*/}}
{{- define "smithy.configMapName" -}}
{{- printf "%s-config" (include "smithy.fullname" .) }}
{{- end }}

{{/*
Namespace used for task Pods. Falls back to the release namespace.
*/}}
{{- define "smithy.taskNamespace" -}}
{{- default .Release.Namespace .Values.runtime.kubernetes.namespace }}
{{- end }}
