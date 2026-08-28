# Evidence — helm-chart-deployability

Commit under test: `29da944` (`docs(helm): document deploying the gateway on
Kubernetes`, on top of `bb5e01c`). One fresh run of every gate after the last
code edit.

## Gates

### `./mvnw clean verify`

```
[INFO] BUILD SUCCESS
[INFO] Total time:  01:21 min
[INFO] Finished at: 2026-08-28T11:12:40+02:00
```

### `(cd src/main/frontend && pnpm test:stories)`

```
      Tests  6 passed (6)
   Duration  2.34s
```

### `(cd src/main/frontend && pnpm e2e)`

```
  ✓  12 [chromium] › e2e/portal.spec.ts:572:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (298ms)

  12 passed (29.6s)
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
109/109 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 27 passed, 0 failed (27 items)
```

### `mkdocs build --strict`

```
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.63 seconds
```

## Chart

### `helm lint helm/skills-gateway -f full-values.yaml`

```
[INFO] Chart.yaml: icon is recommended

1 chart(s) linted, 0 chart(s) failed
```

`helm lint` with the shipped defaults also passes (it reports the `required` and
`fail` calls as warnings rather than failing the lint).

### `helm template` — the fail-closed path

```
$ helm template t helm/skills-gateway --set postgresql.host=db.example.com \
    --set postgresql.existingSecret=s --set oidc.existingSecret=o

Error: execution error at (skills-gateway/templates/deployment.yaml:132:14):
persistence.mode must be "existingClaim" or "ephemeral" (got ""). Git storage has
no safe default: with "ephemeral" the quarantine, published and hosted
repositories live in an emptyDir and ALL of them are lost when the pod restarts,
while the database still records those snapshots as published -- leaving an
estate the gateway can neither serve nor rehydrate. Choose "existingClaim" and
set persistence.existingClaim for any deployment whose content matters; choose
"ephemeral" only for something you are willing to lose.
```

Two neighbouring paths, checked the same way:

```
$ ... --set persistence.mode=existingClaim
Error: persistence.mode is "existingClaim" but persistence.existingClaim is
empty. Set it to the name of a PersistentVolumeClaim that already exists in the
release namespace.

$ ... --set persistence.mode=pvc
Error: persistence.mode must be "existingClaim" or "ephemeral" (got "pvc"). ...
```

### `helm template` — the `existingClaim` path

With the full values file (private registry, ingress with TLS, layered `config`,
`extraEnv` with a `secretKeyRef`, `extraEnvFrom`), five objects render —
ConfigMap, ServiceAccount, Service, Deployment, Ingress — with:

```yaml
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: "skills-gateway-data"
        - name: tmp
          emptyDir: {}
        - name: config
          configMap:
            name: t-skills-gateway-config
```

and, on the container:

```yaml
            - name: SPRING_CONFIG_ADDITIONAL_LOCATION
              value: "optional:file:/etc/skills-gateway/"
            - name: SKILLSGATEWAY_RETENTION_ENABLED
              value: "true"
            - name: SGW_ESTATE_CI_BOT_SECRET
              valueFrom:
                secretKeyRef:
                  key: ci-bot-secret
                  name: skills-gateway-estate
          envFrom:
            - secretRef:
                name: skills-gateway-extra
```

`... --set persistence.mode=ephemeral` renders `emptyDir: {}` for `data`.

## Proving the new assertions fail before they pass

Each break was applied to the committed chart, `PackagingTests` run, then the
file restored with `git checkout --`. Every break failed the intended test **and
no other** — the count is `Failures: 1` throughout, and the named test is the one
whose requirement the break violates.

| Break | Test that failed | Line |
| --- | --- | --- |
| A — the data volume falls back to `emptyDir` again | `chartRefusesToRenderWithoutAnExplicitStorageDurabilityChoice` (`SVC_GW_0120`) | 69 |
| B — an unrecognised mode silently yields `emptyDir` instead of failing | same | 92 |
| C — the ingress is enabled by default | `chartCarriesRegistryCredentialsAnOptionalIngressAndDefaultReservations` (`SVC_GW_0121`) | 122 |
| D — the pod spec drops `imagePullSecrets` | same | 111 |
| E — the pod runs as the namespace `default` service account | same | 151 |
| F — the root filesystem is writable | same | 160 |
| G — `extraEnv` never reaches the container | `chartPassesArbitraryApplicationConfigurationThrough` (`SVC_GW_0122`) | 187 |
| G2 — `envFrom` sources never reach the container | same | 188 |
| H — the layered configuration location points nowhere | same | 192 |
| I — a configuration change does not roll the pods | same | 201 |

Sample output:

```
=================== BREAK A: data volume falls back to emptyDir
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE!
[ERROR]   PackagingTests.chartRefusesToRenderWithoutAnExplicitStorageDurabilityChoice:69

=================== BREAK B: an unrecognised storage mode silently yields emptyDir
[ERROR]   PackagingTests.chartRefusesToRenderWithoutAnExplicitStorageDurabilityChoice:92
          [an unrecognised or absent choice fails the render]

=================== BREAK I: a configuration change does not roll the pods
[ERROR]   PackagingTests.chartPassesArbitraryApplicationConfigurationThrough:201
          [a configuration change must roll the pods]

=================== RESTORED: all breaks reverted
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

### One assertion was caught by this exercise

Break G passed on the first attempt. The cause was the assertion, not the chart:
`assertThat(deployment).contains(".Values.extraEnv")` is satisfied by
`.Values.extraEnvFrom`, which is a superstring of it — so the assertion stayed
green with the `extraEnv` block deleted. It now matches `.Values.extraEnv }}`
with the closing brace, and breaks G and G2 fail independently. This is the
reason for running the breaks rather than asserting that they would fail.

## Not weakened

The four pre-existing tests (`SVC_GW_0015`, `SVC_GW_0072`, `SVC_GW_0108`,
`SVC_GW_0109`) are untouched; the suite went from 4 to 7 tests, and the count in
every run above is `Tests run: 7`.
