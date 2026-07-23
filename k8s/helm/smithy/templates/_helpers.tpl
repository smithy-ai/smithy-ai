{{/*
Chart name (overridable).
*/}}
{{- define "smithy.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "smithy.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "smithy.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels.
*/}}
{{- define "smithy.labels" -}}
helm.sh/chart: {{ include "smithy.chart" . }}
{{ include "smithy.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "smithy.selectorLabels" -}}
app.kubernetes.io/name: {{ include "smithy.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
ServiceAccount name.
*/}}
{{- define "smithy.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "smithy.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Component names.
*/}}
{{- define "smithy.orchestrator.fullname" -}}
{{- printf "%s-orchestrator" (include "smithy.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "smithy.knowledgebase.fullname" -}}
{{- printf "%s-knowledgebase" (include "smithy.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Image references (fall back to the shared registry/tag).
*/}}
{{- define "smithy.orchestrator.image" -}}
{{- $repo := .Values.orchestrator.image.repository | default (printf "%s/orchestrator" .Values.image.registry) -}}
{{- $tag := .Values.orchestrator.image.tag | default .Values.image.tag -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}

{{- define "smithy.knowledgebase.image" -}}
{{- $repo := .Values.knowledgebase.image.repository | default (printf "%s/knowledgebase" .Values.image.registry) -}}
{{- $tag := .Values.knowledgebase.image.tag | default .Values.image.tag -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}

{{- define "smithy.taskImage" -}}
{{- .Values.config.taskImage | default (printf "%s/claude-task-default:%s" .Values.image.registry .Values.image.tag) -}}
{{- end -}}

{{/*
In-cluster FQDN of the knowledgebase service. Task containers run inside DinD
and resolve names through Docker's embedded DNS -> kube-dns, so short names
without the cluster search domain do not resolve. Always use the FQDN.
*/}}
{{- define "smithy.knowledgebase.url" -}}
{{- printf "http://%s.%s.svc.cluster.local:%d/mcp" (include "smithy.knowledgebase.fullname" .) .Release.Namespace (int .Values.knowledgebase.service.port) -}}
{{- end -}}
