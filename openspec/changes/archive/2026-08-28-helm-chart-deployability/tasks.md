## 1. Requirements (reqstool SSOT first)

- [x] 1.1 Add `GW_0120` (explicit storage durability choice, chart fails closed) to `docs/reqstool/requirements.yml` with `implementation: configuration`
- [x] 1.2 Add `GW_0121` (deployable chart surface: registry credentials, optional ingress, default reservations, own service account, non-root least-privilege defaults)
- [x] 1.3 Add `GW_0122` (configuration passthrough; role enforcement off by default must be documented)
- [x] 1.4 Add `SVC_GW_0120`–`SVC_GW_0122` to `docs/reqstool/software_verification_cases.yml` as `verification: automated-test`

## 2. Configuration passthrough (GW_0122, the headline — #132)

- [x] 2.1 Verify how the application actually resolves configuration before choosing a mechanism: no `spring.config.import`/`location` in the app, no config build arg in the native profile, locations resolved at runtime
- [x] 2.2 Verify `EstateBootstrap` ordering (`SmartInitializingSingleton`, before the web server starts) so a mounted file is present in time
- [x] 2.3 Add `config` to `values.yaml` and render `templates/configmap.yaml` from it
- [x] 2.4 Mount it at `/etc/skills-gateway` and set `SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/etc/skills-gateway/`
- [x] 2.5 Add `extraEnv` (supporting `valueFrom`) and `extraEnvFrom` (`secretRef`/`configMapRef`)
- [x] 2.6 Hash `config` into a `checksum/config` pod annotation so a configuration edit rolls the pods

## 3. Fail-closed storage (GW_0120)

- [x] 3.1 Replace `persistence.existingClaim`-or-`emptyDir` with `persistence.mode` (`existingClaim` | `ephemeral`), no default
- [x] 3.2 Move the volume source into `skills-gateway.storageVolume`, failing the render on an absent or unrecognised mode and on `existingClaim` with no claim
- [x] 3.3 Make the failure message state the data-loss consequence, not just the rule

## 4. Deployable surface (GW_0121)

- [x] 4.1 `imagePullSecrets` on the pod spec
- [x] 4.2 `templates/ingress.yaml` behind `ingress.enabled` (default false) with class, annotations, hosts, paths, TLS
- [x] 4.3 Default `resources` requests and limits, commented as a starting point
- [x] 4.4 `templates/serviceaccount.yaml` plus `serviceAccount.create/name/annotations` and `skills-gateway.serviceAccountName`
- [x] 4.5 Non-root pod and container security contexts, all capabilities dropped, read-only root filesystem, `emptyDir` at `/tmp`

## 5. Tests

- [x] 5.1 `chartRefusesToRenderWithoutAnExplicitStorageDurabilityChoice` (`SVC_GW_0120`)
- [x] 5.2 `chartCarriesRegistryCredentialsAnOptionalIngressAndDefaultReservations` (`SVC_GW_0121`)
- [x] 5.3 `chartPassesArbitraryApplicationConfigurationThrough` (`SVC_GW_0122`)
- [x] 5.4 Prove each new assertion fails before it passes: nine deliberate chart breaks, each failing the intended test and no other; recorded in `evidence.md`
- [x] 5.5 No existing assertion weakened — the pre-existing `SVC_GW_0015`, `SVC_GW_0072`, `SVC_GW_0108`, `SVC_GW_0109` tests are untouched

## 6. Documentation

- [x] 6.1 `docs/manual/guides/deploying-on-kubernetes.md`: prerequisites, configuration passthrough with the `roles.enabled` warning, storage trade-offs, ingress/TLS, `replicaCount` must stay 1, identity and security context, worked serverless example, verification
- [x] 6.2 State that estate YAML is the only non-interactive configuration path while the admin API is session-only (#128)
- [x] 6.3 State honestly that a network filesystem is a poor substrate for git, and point at #127 for the production answer
- [x] 6.4 Point `declarative-estate.md` at how the declaration reaches a deployed gateway
- [x] 6.5 One nav line in `mkdocs.yml`
- [x] 6.6 Move the serverless storage-options comparison into the guide, where an operator choosing between them will actually read it, rather than leaving it in issue comments and change proposals that archive away — every availability claim verified against the vendor's own documentation first, and phrased so a reader on another platform can still use it

## 7. Gates

- [x] 7.1 `helm lint` and `helm template` for the fail-closed and `existingClaim` paths
- [x] 7.2 All repository gates fresh after the last edit; `evidence.md` written
