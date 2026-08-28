# Design

## Why the storage choice is a mode, not a stricter default

Three shapes were considered for making a careless install safe.

1. **Make `existingClaim` required.** Rejected: it removes the throwaway install
   entirely, and a demo or a CI environment legitimately wants an `emptyDir`.
2. **Have the chart create a PVC.** Rejected: the interesting decisions —
   storage class, access mode, size, static versus dynamic provisioning — belong
   to the cluster. On serverless node pools dynamic provisioning is not even
   available, so a chart-created PVC would be wrong exactly where the first
   deployment target is.
3. **An explicit `persistence.mode` with no default, failing the template on
   anything else.** Chosen.

`mode` is deliberately a word rather than a boolean: `persistence.enabled:
false` reads like "no storage configured yet", whereas `mode: ephemeral` is a
sentence somebody had to write. The failure message carries the consequence, not
just the rule — the reader of a `helm install` error is usually the person who
does not yet know that the database and the filesystem are two halves of one
estate.

The fail lives in a named template (`skills-gateway.storageVolume`) that renders
the volume source, so there is exactly one place the choice is made and no
second path to an `emptyDir`. The packaging test asserts on that template's
branches rather than on the file as a whole, which is what ties "an `emptyDir`
is produced" to "and only when `ephemeral` was asked for".

## Why configuration is both a file and environment variables

Neither mechanism alone covers the surface:

- The declarative estate is five nested lists of objects. As relaxed-binding
  environment variables that is `SKILLSGATEWAY_ESTATE_MARKETPLACES_0_NAME` and
  worse — unreadable, and hostile to review in a values file. It needs a file.
- Secrets must not be in a ConfigMap. They need `valueFrom.secretKeyRef` or a
  `secretRef`, which is environment territory. The estate's own secrets are
  already `${ENV_VAR}` placeholders, so the file and the environment compose:
  the file names the placeholder, the environment supplies it from a Secret.

`SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/etc/skills-gateway/` layers the
mounted file *over* the image's `application.yaml` rather than replacing it, so
the image stays authoritative for defaults. `optional:` means a missing mount
degrades to the image's own configuration instead of failing to boot.

Verified against the runtime before committing to it: there is no
`spring.config.import` or `spring.config.location` anywhere in the application,
the native build passes no config-location build arg, and config locations are
resolved at runtime rather than baked at image-build time. The one real
constraint is unrelated to this mechanism — an auto-configuration condition
evaluated during AOT (the `idp` client registration must exist at build time)
cannot be flipped by external configuration, only its values overridden. Nothing
in `skills-gateway.*` is in that class.

`EstateBootstrap` reconciles through `SmartInitializingSingleton`, which runs
after the singletons exist and *before* the web server starts, so a file mounted
by the kubelet at container start is present in time — the mount is established
before the process is executed. The corollary is that a ConfigMap edit alone is
useless: the kubelet refreshes the file in place, and nothing re-reads it. Hence
the `checksum/config` pod annotation, which turns a configuration edit into a
rollout.

## Security context and the read-only root filesystem

The image already runs as distroless `nonroot` (uid 65532); the chart states it
so an admission policy has something to admit, and adds `fsGroup` so the mounted
data volume is writable by that user. `readOnlyRootFilesystem: true` is only
safe with somewhere to write: JGit writes temporary files, so the chart mounts an
`emptyDir` at `/tmp` unconditionally rather than leaving that to be discovered in
production.

## What is deliberately not here

- **No new storage backend.** The filesystem stays the source of truth; #127
  owns replacing it, and until then `replicaCount` must stay 1 because nothing
  arbitrates two writers.
- **No chart-managed Secrets.** Every credential is referenced by the name of a
  Secret the operator already created. A chart that templates secrets from
  values puts them in release history.
- **No estate object type is added**, so the declarative-estate obligation in
  `CLAUDE.md` is satisfied by making the existing estate reachable rather than by
  extending it.
