# Tasks: pluggable-git-storage

Trust-boundary change (the published repository is the served surface;
`ApprovalService` is the sole publisher), so `.claude/skills/old-coder`
applies: failing tests first, proved failing, before any implementation.

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0111 (backend named not inferred, fail-closed startup,
      credentials without IMDS), GW_0112 (atomic uncoordinated reference
      transitions), GW_0114 (verified migration) and GW_0115 (replication
      refused where unsafe) to `docs/reqstool/requirements.yml`. GW_0113 is
      dropped — #134 shipped it as GW_0120.
      **GW_0112 landed with the contract suite (section 3).** The other three
      ids are verified free against `main` (which now carries GW_0120–GW_0122
      and GW_0125–GW_0131, so 0111–0119 and 0123–0124 are unclaimed) and stay
      reserved: `reqstool status` counts a requirement with no `@Requirements`
      annotation and no passing SVC test as incomplete and fails the gate, so
      each of GW_0111, GW_0114 and GW_0115 lands in the section that
      implements it — 5.3, 7.2 and 8.3 respectively
- [ ] 1.2 Add the matching SVCs (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`.
      **SVC_GW_0112 landed with section 3**; the rest follow their
      requirements

## 2. Spike (settle the open questions before writing production code)

- [x] 2.1 **Test-double fidelity spike — do this before any other object-store
      work.** Against Floci (`docker.io/floci/floci`) through the Arconia Floci
      dev service, prove
      `If-Match` semantics exactly: matching ETag succeeds and returns a new
      ETag; stale ETag returns **412** with the stored object unchanged;
      `If-None-Match: *` creates once and 412s on the second attempt; under N
      concurrent writers from one base ETag exactly one succeeds. A double that
      ignores `If-Match` would let a broken backend pass its concurrency tests
      green, so nothing downstream may be written until this is settled.
      **Done — Floci 1.5.33 passes all five through the Arconia Floci dev
      service, and two mutations confirm the suite would have caught a store
      that ignores preconditions: dropping `If-Match` fails 3 of 5 including
      the concurrency case (`expected: 1L`), dropping `If-None-Match` fails the
      create-once case (`ConditionalWriteFidelityTests`)**
- [x] 2.2 **Not triggered — 2.1 passed.** Fallback kept on record: if a future
      emulator version regresses, move the conditional-write contract to a
      separate tagged suite against real S3 and consider contributing the
      behaviour upstream
- [ ] 2.3 Run the same five assertions against **real AWS S3**, and promote
      that row of the decision-9 table from believed to verified. Floci passing
      proves the design is implementable and our tests are meaningful; it does
      not certify the production target, and the supported-store list is not
      publishable until this is done. **Not attempted here:** it needs an
      account this change does not have and deliberately did not acquire, so
      the row stays documented-but-unexercised.

      **MinIO was dropped as the second store, deliberately.** It was probed —
      `RELEASE.2025-07-23T15-54-02Z` passes all five assertions across three
      consecutive runs and fails under the same two mutations that discriminated
      Floci — so the portability evidence is real and is kept in decision 9. What
      it is not is a foundation to build CI on: MinIO stopped publishing free
      container images around October 2025, so any pin would be to a frozen tag
      with no upgrade path and no guarantee the tag stays hosted. A gate whose
      image supply has already stopped is a gate that fails later, without
      warning, for reasons unrelated to the code. Floci remains the single
      in-build store; real S3 remains the row that actually matters.

- [x] 2.4 Decide the ref database shape — `DfsReftableDatabase` over the
      bucket versus a plain `DfsRefDatabase` over the manifest — and record the
      decision in `design.md`. **Decided: the plain `DfsRefDatabase` over the
      manifest** (design decision 10). Researched against JGit 7.7.1 on the
      classpath, then reviewed independently: the review upheld the conclusion,
      corrected three factual claims, and added five consequences — of which two
      are design gaps that must be honoured in task 6.3 rather than discovered
      there: `compareAndPut` must absorb the per-ref versus whole-manifest
      precondition mismatch internally (or disjoint-ref writes fail spuriously
      with `LOCK_FAILURE`), and cross-replica revocation needs a stated,
      tested bound (or an unpublished snapshot stays fetchable from any replica
      with a warm cache).

- [ ] 2.5 Prove an AWS SDK v2 S3 client with the web-identity credential
      provider builds and runs under the GraalVM native-image profile
- [ ] 2.6 Prove a `receive-pack` (hosted-marketplace push, many refs in one
      transaction) maps onto a single manifest transition

## 3. The backend contract test (write it before either backend uses it)

- [x] 3.1 `GitStorageContractTests`, parameterized over every `GitStorage`
      implementation: the three repository roles, `hostedIfPresent` and
      `publishedIfServing` emptiness rules, `HEAD` linked to `refs/heads/main`
      on creation, and publication making exactly the two refs appear.
      **Also carries the two cases decision 10's review added:** a refused ref
      transition must be raised rather than swallowed (result codes, not end
      states), and the `atomic` push capability must be *advertised* — read off
      a `receive-pack` advertisement, since `ReceivePack.service()` overwrites
      the server-side flag from what the client enabled
- [x] 3.2 `unpublish` cases in the same suite, annotated
      `@SVCs({"SVC_GW_0112"})`: `refs/snapshots/{sha}` always removed;
      `refs/heads/main` removed only when it still resolves to that SHA;
      the returned boolean true exactly when the tip was removed; revoking a
      superseded snapshot leaves the marketplace serving
- [x] 3.3 Run the suite against `FilesystemGitStorage` and confirm it passes —
      the contract is a description of today's behavior before it is a
      requirement on a new backend. **It did not pass unchanged:** the
      result-code case failed RED against `FilesystemGitStorage.deleteRef`,
      which discarded `RefUpdate.delete()`'s result, so a `LOCK_FAILURE` on the
      revocation path returned as success. Fixed in the same section — the
      defect the review predicted for the object store was already present on
      the filesystem

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
      `newPack`, `writeFile`, `commitPackImpl`, `rollbackPack`) and the ref database
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
- [ ] 6.8 Flip the Floci dev-service dependency from `test` scope to `optional`,
      alongside the Postgres dev service, once this backend gives `bootRun` an
      object-store consumer to share the container with. The dev service itself
      is already in use — `ConditionalWriteFidelityTests` takes its `S3Client`
      from the context (task 2.1) — so this is the remaining half of the rule,
      not the adoption. **Deferred to the backend, not blocked:** with no
      application code touching object storage, an `optional` dependency would
      start a Floci container on every `bootRun` for nothing

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
