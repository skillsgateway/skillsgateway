# Tasks: pluggable-git-storage

Trust-boundary change (the published repository is the served surface;
`ApprovalService` is the sole publisher), so `.claude/skills/old-coder`
applies: failing tests first, proved failing, before any implementation.

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0111 (backend named not inferred, fail-closed startup,
      credentials without IMDS), GW_0112 (atomic uncoordinated reference
      transitions), GW_0114 (verified migration) and GW_0115 (replication
      refused where unsafe) to `docs/reqstool/requirements.yml`. GW_0113 is
      dropped — #134 shipped it as GW_0120
- [ ] 1.2 Add the matching SVCs (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Spike (settle the open questions before writing production code)

- [x] 2.1 **Test-double fidelity spike — do this before any other object-store
      work.** Against Floci (`docker.io/floci/floci`) via Testcontainers, prove
      `If-Match` semantics exactly: matching ETag succeeds and returns a new
      ETag; stale ETag returns **412** with the stored object unchanged;
      `If-None-Match: *` creates once and 412s on the second attempt; under N
      concurrent writers from one base ETag exactly one succeeds. A double that
      ignores `If-Match` would let a broken backend pass its concurrency tests
      green, so nothing downstream may be written until this is settled.
      **Done — Floci 1.5.28 passes all five, and two mutations confirm the
      suite would have caught a store that ignores preconditions
      (`ConditionalWriteFidelityTests`)**
- [x] 2.2 **Not triggered — 2.1 passed.** Fallback kept on record: if a future
      emulator version regresses, move the conditional-write contract to a
      separate tagged suite against real S3 and consider contributing the
      behaviour upstream
- [ ] 2.3 Run the same five assertions against **real AWS S3** and against
      **MinIO**, and promote those rows of the decision-9 table from believed to
      verified. Floci passing proves the design is implementable and our tests
      are meaningful; it does not certify the production target, and the
      supported-store list is not publishable until this is done
- [ ] 2.4 Decide the ref database shape — `DfsReftableDatabase` over the
      bucket versus a plain `DfsRefDatabase` over the manifest — and record the
      decision in `design.md`
- [ ] 2.5 Prove an AWS SDK v2 S3 client with the web-identity credential
      provider builds and runs under the GraalVM native-image profile
- [ ] 2.6 Prove a `receive-pack` (hosted-marketplace push, many refs in one
      transaction) maps onto a single manifest transition

## 3. The backend contract test (write it before either backend uses it)

- [ ] 3.1 `GitStorageContractTests`, parameterized over every `GitStorage`
      implementation: the three repository roles, `hostedIfPresent` and
      `publishedIfServing` emptiness rules, `HEAD` linked to `refs/heads/main`
      on creation, and publication making exactly the two refs appear
- [ ] 3.2 `unpublish` cases in the same suite, annotated
      `@SVCs({"SVC_GW_0112"})`: `refs/snapshots/{sha}` always removed;
      `refs/heads/main` removed only when it still resolves to that SHA;
      the returned boolean true exactly when the tip was removed; revoking a
      superseded snapshot leaves the marketplace serving
- [ ] 3.3 Run the suite against `FilesystemGitStorage` and confirm it passes —
      the contract is a description of today's behavior before it is a
      requirement on a new backend

## 4. Failing tests for the new behavior (prove they fail)

- [ ] 4.1 Backend selection tests annotated `@SVCs({"SVC_GW_0111"})`: absent
      value yields `FilesystemGitStorage`; unrecognised value fails context
      startup naming the accepted values; `object-store` with no bucket,
      endpoint or credential mode fails startup; no case resolves a backend
      other than the one named
- [ ] 4.2 Concurrency tests annotated `@SVCs({"SVC_GW_0112"})` against Floci
      (only once task 2.1 has proved it honours `If-Match`):
      N concurrent publications and revocations of the same and of superseding
      snapshots; assert no lost update, that exactly one caller is told its
      removal stopped the serving, and that objects are durable before any ref
      names them (kill the writer between upload and manifest write)
- [ ] 4.3 Migration tests annotated `@SVCs({"SVC_GW_0114"})`: full copy across
      all three roles, ref-set comparison, refusal on a deliberately damaged
      copy, source left byte-identical, and a round trip back
- [ ] 4.4 Packaging tests annotated `@SVCs({"SVC_GW_0115"})`: `replicaCount > 1`
      fails on `filesystem`; fails on `object-store` with the uncoordinated
      pollers enabled; renders with them disabled. (#134's `PackagingTests`
      already cover the fail-closed `persistence.mode`; extend, never weaken)
- [ ] 4.5 Record the failing run (old-coder evidence) before any implementation

## 5. Configuration surface

- [ ] 5.1 `SkillsGatewayProperties.Storage(Backend backend, ObjectStore
      objectStore)` with `Backend` = `FILESYSTEM` (default) | `OBJECT_STORE`
- [ ] 5.2 `ObjectStore(endpoint, region, bucket, prefix, Credentials
      credentials, cache)` and `Credentials(mode, ...)` with mode `DEFAULT` |
      `WEB_IDENTITY` | `STATIC`; validation that fails startup on an
      incomplete `OBJECT_STORE` selection
- [ ] 5.3 `GitStorageConfiguration` selecting the single `GitStorage` bean;
      annotate the selection path `@Requirements({"GW_0111"})`

## 6. The object-storage backend

- [ ] 6.1 Object-store client abstraction over the S3 SDK: get, ranged get,
      put, conditional put (`If-Match` / `If-None-Match`), list, delete —
      plus the startup probe for read-after-write and conditional writes
- [ ] 6.2 Manifest and write-ahead log: immutable content-named packs, `wal/`
      entries per transition, `manifest` holding the ref map, WAL sequence and
      live pack set; every transition a single conditional write with re-read
      and retry on conflict
- [ ] 6.3 `DfsObjDatabase` implementation (`listPacks`, `openFile`,
      `writePackFile`, `commitPackImpl`, `rollbackPack`) and the ref database
      chosen in task 2.4; `DfsRepository` subclass wiring the two
- [ ] 6.4 `ObjectStoreGitStorage implements GitStorage` — the three roles and
      `unpublish` evaluated and committed as one transition; annotate
      `@Requirements({"GW_0112"})`
- [ ] 6.5 Bounded on-disk pack cache under `data-dir` plus `DfsBlockCache`
      sizing; deleting the cache must be safe at any time
- [ ] 6.6 Compaction: WAL entries folded into the manifest, small packs via
      `DfsPackCompactor` / `DfsGarbageCollector`, itself conditional-write
      guarded so a losing compactor loses harmlessly
- [ ] 6.7 Make the whole contract test suite (task 3) green against this
      backend with no change to any `GitStorage` caller
- [ ] 6.8 Switch `ConditionalWriteFidelityTests` (and any later object-store
      test) from `io.floci:testcontainers-floci` to the Arconia Floci dev
      service, per the project rule that one container serves `bootRun` and the
      suite. Blocked until now only because the dev service's auto-configuration
      is `@ConditionalOnClass` on Spring Cloud AWS, whose own auto-configuration
      then fails every gateway context for want of an `AwsRegionProvider`; once
      this backend configures an AWS region and credentials for real, that
      conflict is gone. Re-run both mutation proofs after the switch

## 7. Migration

- [ ] 7.1 Migration command copying all repositories in all three roles
      between backends, streaming rather than loading whole repositories
- [ ] 7.2 Verification pass comparing resolved ref sets per repository,
      refusing success on any mismatch and naming the repository; annotate
      `@Requirements({"GW_0114"})`
- [ ] 7.3 Confirm the source backend is untouched and the reverse direction
      works, so reverting the property restores the previous state

## 8. Packaging

- [ ] 8.1 `values.yaml`: add `none` to the existing `persistence.mode`
      (`existingClaim` | `ephemeral` from #134), plus `storage.backend`,
      `storage.objectStore.*`, IRSA annotations on the service account #134
      added, and `existingSecret` as the static-credential fallback
- [ ] 8.2 `deployment.yaml`: extend the `_helpers.tpl` storage-volume logic for
      `none` (refusing it unless the backend is `object-store`); project the
      credential environment
- [ ] 8.3 Replica gating: refuse `replicaCount > 1` on `filesystem`, and on
      `object-store` unless the uncoordinated pollers are disabled
- [ ] 8.4 Extend the existing packaging consistency test to cover 8.1–8.3;
      chart version bump and a release note naming the deliberate break
- [ ] 8.5 Add a `packageRules` entry to `.github/renovate.json5` separating
      `org.eclipse.jgit*` from the general dependency stream, so a JGit bump
      arrives as its own deliberately reviewed PR — the DFS extension points
      are `internal` API and can break without breaking ordinary JGit use

## 9. Observability

- [ ] 9.1 Metrics: conditional-write conflicts and retries, WAL depth, live
      pack count, pack cache hit rate, object-store request latency
- [ ] 9.2 A health indicator reporting backend reachability and the
      conditional-write probe result

## 10. Documentation (same PR as the implementation)

- [ ] 10.1 `reference/configuration.md`: the `storage` block, the credential
      modes, and replace the "Helm volume default is not durable" warning with
      what the packaging now refuses
- [ ] 10.2 New operations page: choosing a backend, the Fargate constraints
      (no EBS, EFS static provisioning only, no IMDS), the **supported object
      store list** verified in task 2.3, migrating, and the honest cold-start
      cost
- [ ] 10.3 `concepts/lifecycle.md`: the layout table is filesystem-shaped —
      give it the object-store equivalent without weakening the three-role
      model
- [ ] 10.4 `concepts/trust-boundaries.md`: write access to the bucket is
      publication without `ApprovalService`
- [ ] 10.5 `architecture.md` §12 and the roadmap; `guides/local-development.md`
      (Floci in Compose)
- [ ] 10.6 An ADR if the spike changes any decision in `design.md`

## 11. Gates and evidence

- [ ] 11.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [ ] 11.2 Native-image build with the S3 client and the web-identity provider
- [ ] 11.3 `openspec/changes/pluggable-git-storage/evidence.md` — commands,
      pasted result tails of one final fresh run, and the commit SHA
