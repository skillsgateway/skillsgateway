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
{{- else if eq $mode "none" -}}
{{- if ne .Values.storage.backend "object-store" -}}
{{- fail (printf "persistence.mode is \"none\" but storage.backend is %q. Keeping no volume is only correct when the bucket is the repository; on the filesystem backend it would leave the gateway with nowhere to put the quarantine, published and hosted repositories at all. Choose \"existingClaim\" or \"ephemeral\", or set storage.backend to \"object-store\"." .Values.storage.backend) -}}
{{- end -}}
{{- else -}}
{{- fail (printf "persistence.mode must be \"existingClaim\", \"ephemeral\" or \"none\" (got %q). Git storage has no safe default: with \"ephemeral\" the quarantine, published and hosted repositories live in an emptyDir and ALL of them are lost when the pod restarts, while the database still records those snapshots as published -- leaving an estate the gateway can neither serve nor rehydrate. Choose \"existingClaim\" and set persistence.existingClaim for any deployment whose content matters; choose \"ephemeral\" only for something you are willing to lose; \"none\" mounts no volume at all and is valid only on storage.backend \"object-store\", where the bucket is the repository." $mode) -}}
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

{{/*
Git storage backend, fail-closed.

The gateway takes its backend from a name and never infers one, and refuses to
start when the named backend's settings are incomplete. The chart refuses at
render time for the same reason: an install that names a bucket and forgets the
region should find that out before a pod exists, not from a CrashLoopBackOff.
*/}}
{{- define "skills-gateway.storageGate" -}}
{{- $backend := .Values.storage.backend | default "" -}}
{{- if not (has $backend (list "filesystem" "object-store")) -}}
{{- fail (printf "storage.backend must be \"filesystem\" or \"object-store\" (got %q). There is no inferred backend: a gateway serving from local disk while the operator believes it is serving from a bucket reports healthy and is wrong." $backend) -}}
{{- end -}}
{{- if eq $backend "object-store" -}}
{{- $store := .Values.storage.objectStore -}}
{{- if not $store.bucket -}}
{{- fail "storage.backend is \"object-store\" but storage.objectStore.bucket is empty. The bucket is where every repository would live; the chart will not render a deployment that would fail its own startup check." -}}
{{- end -}}
{{- if not $store.region -}}
{{- fail "storage.backend is \"object-store\" but storage.objectStore.region is empty. An unsigned-for region is a failure in the middle of an approval rather than at startup, so it is required here." -}}
{{- end -}}
{{- $mode := $store.credentials.mode | default "web-identity" -}}
{{- if not (has $mode (list "web-identity" "default" "static")) -}}
{{- fail (printf "storage.objectStore.credentials.mode must be \"web-identity\", \"default\" or \"static\" (got %q)." $mode) -}}
{{- end -}}
{{- if and (eq $mode "static") (not $store.credentials.existingSecret) -}}
{{- fail "storage.objectStore.credentials.mode is \"static\" but storage.objectStore.credentials.existingSecret is empty. Access keys come from a Secret with keys \"access-key-id\" and \"secret-access-key\"; there is deliberately nowhere in values.yaml to type one." -}}
{{- end -}}
{{- if and (eq $mode "web-identity") (not .Values.serviceAccount.annotations) -}}
{{- fail "storage.objectStore.credentials.mode is \"web-identity\" but serviceAccount.annotations is empty. Workload identity is attached by annotating the service account (for EKS, eks.amazonaws.com/role-arn); without it the pod has no credentials and no instance metadata service to fall back to." -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Replica gating.

One obstacle now, and it is storage. The filesystem backend has no cross-pod
locking of any kind, and on an RWX volume nothing but the replica count stands
between the estate and a lost reference update, so more than one replica is
refused outright there.

Coordination used to be the second obstacle, and this template used to name five
switches an operator had to turn off before scaling out. The gateway coordinates
its own sweeps now (GW_FACADE_0030): each takes a lease row for its interval, so
N replicas run one pass, not N. That block is gone rather than relaxed — it also
listed five switches for eight passes, so an operator who set all five still got
N waiver-expiry sweeps and N mirror sweeps, and a gate that promises a safety it
does not deliver is worse than no gate.
*/}}
{{- define "skills-gateway.replicaGate" -}}
{{- $replicas := int (.Values.replicaCount | default 1) -}}
{{- if gt $replicas 1 -}}
{{- if ne .Values.storage.backend "object-store" -}}
{{- fail (printf "replicaCount is %d, but storage.backend is %q. The filesystem backend assumes a single writer and has no cross-pod locking, so a second pod force-updates the served reference with nothing to stop it. Only \"object-store\", whose reference transitions are serialized by a conditional write, supports more than one replica." $replicas .Values.storage.backend) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Forwarded-header strategy, fail-closed: the three values Spring Boot knows.
*/}}
{{- define "skills-gateway.forwardHeadersStrategy" -}}
{{- $strategy := .Values.forwardHeadersStrategy | default "" -}}
{{- if not (has $strategy (list "native" "framework" "none")) -}}
{{- fail (printf "forwardHeadersStrategy must be \"native\", \"framework\" or \"none\" (got %q). It decides whether the gateway believes the scheme and host a proxy reports in X-Forwarded-* headers; a value the gateway does not know would leave every login behind a TLS-terminating Ingress failing on a redirect-URI mismatch." $strategy) -}}
{{- end -}}
{{- $strategy -}}
{{- end -}}
