## Why

The Helm chart cannot deploy a usable gateway, and the way it fails is quiet in
two places.

The first is configuration. The Deployment renders `SPRING_DATASOURCE_*`,
`SGW_OIDC_*` and conditionally `SKILLSGATEWAY_OIDC_ISSUER`, and nothing else —
no `extraEnv`, no `envFrom`, no mounted file
([#132](https://github.com/skillsgateway/skillsgateway/issues/132)). So
`skills-gateway.roles.enabled`, which defaults to `false` and while false lets
every authenticated principal pass every authorization check, cannot be turned
on; and the entire `skills-gateway.estate.*` surface is unreachable. Because the
admin API requires an interactive OIDC session
([#128](https://github.com/skillsgateway/skillsgateway/issues/128)), estate YAML
is the only non-interactive configuration path there is — so the two gaps
compound into a chart-deployed gateway that automation cannot configure at all,
and that hands full administrative rights to anyone who can log in.

The second is storage. `persistence.existingClaim` defaults to `""` and the
Deployment silently falls back to an `emptyDir`. Forgetting one value therefore
buys an install whose quarantine, published and hosted repositories vanish on
the next pod restart, while PostgreSQL keeps recording those snapshots as
published — an estate that cannot be served and cannot be rehydrated, since a
published snapshot is a pinned history rather than something re-fetchable.

Beyond those, the chart has no `imagePullSecrets` (so a private registry is out),
no Ingress (so nothing reaches it), no resource requests, no service account of
its own and no security context.

## What Changes

- **Configuration passthrough.** `config` renders arbitrary `skills-gateway.*`
  YAML into a ConfigMap, mounted and layered over the image's `application.yaml`
  via `SPRING_CONFIG_ADDITIONAL_LOCATION`; `extraEnv` and `extraEnvFrom` carry
  single settings and anything that must come from a Secret. The pod template
  hashes `config`, so changing it rolls the pods — the estate is reconciled at
  startup, so a refreshed ConfigMap that does not restart anything is never
  read.
- **Storage fails closed.** `persistence.mode` must be `existingClaim` or
  `ephemeral`. Anything else — including the empty default the chart ships —
  stops the render with a message naming what is lost. `existingClaim` without a
  claim name is refused too.
- **`imagePullSecrets`** on the pod spec.
- **An optional Ingress**, `ingress.enabled: false` by default, with class,
  annotations, hosts, paths and TLS. TLS is documented as effectively mandatory:
  the facade authenticates with personal access tokens over HTTP Basic.
- **Default `resources`**, and a comment saying they are a starting point.
- **A ServiceAccount of the release's own**, nameable and annotatable, because
  workload identity binds to a named account — the object-storage backend
  ([#127](https://github.com/skillsgateway/skillsgateway/issues/127)) will need
  one.
- **Non-root, least-privilege security contexts** by default, with an `emptyDir`
  at `/tmp` so a read-only root filesystem is not an outage.
- **A deployment guide** covering prerequisites, the configuration passthrough
  and the `roles.enabled` warning, the storage trade-offs, ingress and TLS, why
  `replicaCount` must stay 1, and a worked serverless-Kubernetes example on a
  statically provisioned network filesystem — stated honestly as a proof-of-
  concept substrate, not the production answer.

No Java changes. No new storage backend; that is #127.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `release-packaging`: the chart is the deployable half of distribution, so its
  installability belongs with the artifacts it installs.
  - **GW_0120** (new) — the storage durability choice is explicit and the chart
    fails closed without it.
  - **GW_0121** (new) — the deployable surface: registry credentials, the
    optional ingress, default reservations, an own service account, non-root
    least-privilege defaults.
  - **GW_0122** (new) — configuration passthrough, so every application property
    is reachable from the chart.

## Impact

- `helm/skills-gateway/` — `values.yaml`, `templates/deployment.yaml`,
  `templates/_helpers.tpl`; new `configmap.yaml`, `ingress.yaml`,
  `serviceaccount.yaml`.
- `src/test/java/dev/skillsgateway/server/PackagingTests.java` — three new tests;
  no existing assertion weakened.
- `docs/reqstool/` — `GW_0120`–`GW_0122`, `SVC_GW_0120`–`SVC_GW_0122`.
- `docs/manual/guides/deploying-on-kubernetes.md` (new), a pointer from
  `declarative-estate.md`, one `mkdocs.yml` nav line.
- **Breaking for existing installs of the chart** (not the API): an install that
  relied on the `emptyDir` fallback now has to say `persistence.mode:
  ephemeral`. That is the point of the change; nothing has been released yet, so
  no deployed installation is affected.
