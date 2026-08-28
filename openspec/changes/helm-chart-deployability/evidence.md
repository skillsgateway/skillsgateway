# Evidence — helm-chart-deployability

Commit under test: `f683680` (`docs(helm): compare the serverless storage options
in the deployment guide`, on top of `29da944` and `bb5e01c`). One fresh run of
every gate after the last edit.

## Gates

### `./mvnw clean verify`

```
[INFO] Tests run: 185, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

!!! note "Two runs on this commit failed first, for an environment reason"

    Two earlier attempts on this same commit failed with a single error in a
    *different* test each time — `SessionCredentialExpiryTests`, then
    `ClaimRolesDisabledTests` — both with the same root cause:

    ```
    Caused by: org.testcontainers.containers.ContainerLaunchException: Timed out
    waiting for log output matching '(.*database system is ready to accept
    connections.*\s|...)'
    ```

    The local container VM has 2 GB of memory and an unrelated long-running
    observability stack in it; an `otel-collector` container was OOM-killed
    during one of the runs. The failures are memory pressure starting the
    PostgreSQL test container, not the change: they moved between tests, none of
    them touches the chart, and the run above is clean. Recorded rather than
    quietly re-run, since a green result obtained on the third attempt is worth
    less than one obtained on the first.

### `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (25.8s)
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
INFO    -  Documentation built in 0.65 seconds
```

The guide's two cross-references to the storage comparison were checked against
the built HTML rather than trusted — `--strict` fails on broken *page* links but
not on a missing heading anchor:

```
$ grep -o 'id="storage-options-on-serverless-kubernetes"' \
    site/guides/deploying-on-kubernetes/index.html
id="storage-options-on-serverless-kubernetes"
```

## Sourcing of the storage comparison

Every availability claim in "Storage options on serverless Kubernetes" was
verified against the vendor's own documentation before it was written, not
carried over from the issue thread it came from
([EKS User Guide — AWS Fargate considerations](https://docs.aws.amazon.com/eks/latest/userguide/fargate.html)):

| Claim in the guide | Documented as |
| --- | --- |
| Block storage unavailable | "You can't mount Amazon EBS volumes to Fargate Pods." |
| …and structurally so | "You can run the Amazon EBS CSI controller on Fargate nodes, but the Amazon EBS CSI node DaemonSet can only run on Amazon EC2 instances", with "Daemonsets aren't supported on Fargate." |
| Network filesystem, static provisioning only | "A Pod running on Fargate automatically mounts an Amazon EFS file system… You can't use dynamic persistent volume provisioning with Fargate nodes, but you can use static provisioning." |
| Parallel filesystem unavailable | Comparison table: "Can use Amazon FSx for Lustre storage with Pods — No". |
| No instance metadata service | "The Amazon EC2 instance metadata service (IMDS) isn't available to Pods that are deployed to Fargate nodes… assign them to your Pods using IAM roles for service accounts." |
| Private subnets, egress needs NAT | "Pods that run on Fargate are only supported on private subnets (with NAT gateway access to AWS services, but not a direct route to an Internet Gateway)." |

The RWX caveat (a network filesystem does not enforce the single-writer property
that the current storage relies on) and the packfile-access argument are this
project's own reasoning about its own storage, not vendor claims, and are
written as such. The section names one vendor's products only where it cites
them, and leads with the vendor-neutral category in each row, so a reader on
another platform can still use the table.

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
