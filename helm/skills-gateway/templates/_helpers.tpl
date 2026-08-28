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

{{/*
Git storage volume source (/data), fail-closed.

There is no safe default. An emptyDir fallback loses the quarantine, published
and hosted repositories on every pod restart while the database keeps recording
those snapshots as published -- an estate the gateway can neither serve nor
rehydrate. So the install has to say which it wants, and anything else stops the
render rather than silently producing the lossy one.
*/}}
{{- define "skills-gateway.storageVolume" -}}
{{- $mode := .Values.persistence.mode | default "" -}}
{{- if eq $mode "existingClaim" -}}
{{- if not .Values.persistence.existingClaim -}}
{{- fail "persistence.mode is \"existingClaim\" but persistence.existingClaim is empty. Set it to the name of a PersistentVolumeClaim that already exists in the release namespace." -}}
{{- end -}}
persistentVolumeClaim:
  claimName: {{ .Values.persistence.existingClaim | quote }}
{{- else if eq $mode "ephemeral" -}}
emptyDir: {}
{{- else -}}
{{- fail (printf "persistence.mode must be \"existingClaim\" or \"ephemeral\" (got %q). Git storage has no safe default: with \"ephemeral\" the quarantine, published and hosted repositories live in an emptyDir and ALL of them are lost when the pod restarts, while the database still records those snapshots as published -- leaving an estate the gateway can neither serve nor rehydrate. Choose \"existingClaim\" and set persistence.existingClaim for any deployment whose content matters; choose \"ephemeral\" only for something you are willing to lose." $mode) -}}
{{- end -}}
{{- end -}}

{{/*
Service account the pod runs as. A named account is what a workload-identity
binding (IRSA and its equivalents) attaches to, so the name exists even when the
chart is not the thing creating the account.
*/}}
{{- define "skills-gateway.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- .Values.serviceAccount.name | default (include "skills-gateway.fullname" .) -}}
{{- else -}}
{{- .Values.serviceAccount.name | default "default" -}}
{{- end -}}
{{- end -}}

{{/*
Name of the ConfigMap holding the layered application configuration.
*/}}
{{- define "skills-gateway.configMapName" -}}
{{- printf "%s-config" (include "skills-gateway.fullname" .) -}}
{{- end -}}
