{{/*
Chart name.
*/}}
{{- define "skills-gateway.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name.
*/}}
{{- define "skills-gateway.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels.
*/}}
{{- define "skills-gateway.labels" -}}
app.kubernetes.io/name: {{ include "skills-gateway.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Selector labels.
*/}}
{{- define "skills-gateway.selectorLabels" -}}
app.kubernetes.io/name: {{ include "skills-gateway.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
