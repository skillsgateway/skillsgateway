## 1. Establish the served surface before changing it

- [x] 1.1 Enumerate every ref namespace present in each served role (published,
      catalog, hosted) and name its consumer, so the allowlist is derived rather
      than guessed. Settles design open question 2 (does any hosted flow fetch
      through `/git/**`).
- [x] 1.2 Two tests, with different jobs. (a) A *characterization* test that an
      approved `refs/snapshots/<sha>` is advertised and fetchable by name — this
      passes today, is deliberate behaviour, and exists so the allowlist cannot
      silently over-restrict. (b) A *failing* test proving a `refs/catalog/*` ref
      is advertised on the served surface today — the real exposure `GW_0134`
      closes. Prove (b) red before fixing it.
- [x] 1.3 Write a failing test proving the current `ApprovalService` reports
      `Approved`, writes the ledger entry and emits the webhook when the
      `refs/heads/main` update is refused. This is the #149 defect; it must be red
      before any fix lands.

## 2. The advertisement allowlist (GW_0134 / SVC_GW_0134)

- [x] 2.1 Add a `RefFilter` to `GitFacadeConfiguration`'s `UploadPack` restricting
      advertisement to `refs/heads/main` and `refs/snapshots/*`. Leave
      `GitPublishConfiguration` untouched.
- [x] 2.2 Annotate with `@Requirements({"GW_0134"})`.
- [x] 2.3 Turn 1.2's catalog test green; add a real-client test that clone and
      fetch-by-SHA still work and that no other namespace is advertised.
- [x] 2.4 Prove the check discriminates: mutate the filter to allow everything and
      confirm the tests fail.

## 3. Publication joins the seam (GW_0132 / SVC_GW_0132)

- [x] 3.1 Add `boolean publish(String marketplace, String sha)` to `GitStorage`,
      documenting it as the inverse of `unpublish` and its return as "this call is
      what started the marketplace serving".
- [x] 3.2 Implement object transfer into the unadvertised `refs/staging/<sha>`
      namespace, shared across backends.
- [x] 3.3 Implement the atomic transition in `FilesystemGitStorage` as a batched
      ref update with every result checked.
- [x] 3.4 Implement it in `ObjectStoreGitStorage` as one `ManifestStore.transact`
      with both edits, mirroring `unpublish`.
- [x] 3.5 Annotate both implementations with `@Requirements({"GW_0132", "GW_0112"})`.
- [x] 3.6 Extend the storage contract suite: publication lands both refs or
      neither; a refused transition raises rather than reporting success; the
      staging ref is gone afterwards. Runs against both backends.
- [x] 3.7 Extend the concurrency suite with publish-races-revoke — the
      interleaving that surfaced #149 — and publish-races-publish.
- [x] 3.8 Prove the new contract cases fail against a deliberately non-atomic
      implementation (write one ref, skip the other) before trusting them.

## 4. Approval uses the seam and repairs on failure (GW_0133 / SVC_GW_0133)

- [x] 4.1 Add a state-guarded transition to `SnapshotRepository` returning an
      `approved` row to `held` and restoring the `revoked_at`, `revoked_by` and
      `violation` values captured before `decide()`. Guard with
      `WHERE id = :id AND state = 'approved'`.
- [x] 4.2 Replace the raw fetch and `forceUpdate` in `ApprovalService.doApprove`
      with the seam call; on failure repair the row, then raise.
- [x] 4.3 Raise both causes together when the repair itself fails, and log that
      the estate is inconsistent.
- [x] 4.4 Annotate with `@Requirements({"GW_0133"})`.
- [x] 4.5 Turn 1.3's test green. Add adversarial cases: a refused publication
      produces no `Approved`, no ledger entry, no webhook, nothing fetchable by
      `main` or by SHA, and a row still `held`; a refused re-publish of a
      previously revoked snapshot leaves its revocation stamps intact.
- [x] 4.6 Cover the first-ever approval of a marketplace, where a refusal leaves
      the repository unserved rather than partially served.
- [x] 4.7 Confirm no compensating ref deletion is needed — assert the published
      repository holds neither ref after a refusal.

## 5. The remaining raw ref updates (GW_0135, GW_0136, GW_0137)

- [x] 5.1 Extract one checked ref-update helper, moving `StorageMigration`'s
      `WRITTEN` and `FilesystemGitStorage`'s `DELETED` sets into it; leave both
      call sites behaviourally unchanged.
- [ ] 5.2 Check the result in `CatalogService.pruneInternalRefs` and
      `CatalogService.rebuild`; annotate `@Requirements({"GW_0135"})`.
- [ ] 5.3 Check the pin delete in `RetentionService`, so a purge that cannot
      remove the pin does not proceed to purge the row and write the ledger entry;
      annotate `@Requirements({"GW_0136"})`.
- [ ] 5.4 Check the pin in `IngestionService`; annotate `@Requirements({"GW_0137"})`.
- [ ] 5.5 Adversarial tests for each: a refused update must fail its operation,
      and the retention case must leave the row unpurged and the ledger silent.
- [ ] 5.6 Sweep the repository for any remaining discarded `update()`,
      `forceUpdate()` or `delete()` result and assert in a test that none exists.

## 6. Staging hygiene

- [ ] 6.1 Decide and implement design open question 1: sweep stale
      `refs/staging/*` in retention, or rely on overwrite-on-next-publish. Record
      the decision in design.md.
- [ ] 6.2 Test that a staging ref left behind serves nothing and does not block a
      later publication of the same sha.

## 7. Requirements and traceability

- [ ] 7.1 Author `GW_0132`–`GW_0137` in `docs/reqstool/requirements.yml`.
- [ ] 7.2 Author `SVC_GW_0132`–`SVC_GW_0137` in
      `docs/reqstool/software_verification_cases.yml`.
- [ ] 7.3 Annotate every new test with `@SVCs`.
- [ ] 7.4 `reqstool status local -p docs/reqstool` ends PASS after
      `./mvnw clean verify`.

## 8. Documentation

- [ ] 8.1 `docs/manual/guides/approving-snapshots.md` — the failure mode: an
      approval can fail with the snapshot still held, and what to do.
- [ ] 8.2 `docs/manual/guides/storage-backends.md` — publication is atomic on both
      refs, and a lost compare-and-swap is an ordinary multi-replica outcome.
- [ ] 8.3 `docs/manual/reference/git-facade.md` — advertisement is an explicit
      allowlist; state the two namespaces.

## 9. Gates and evidence

- [ ] 9.1 `./mvnw clean verify`
- [ ] 9.2 `(cd src/main/frontend && pnpm test:stories)`
- [ ] 9.3 `(cd src/main/frontend && pnpm e2e)`
- [ ] 9.4 `reqstool status local -p docs/reqstool` — ends PASS
- [ ] 9.5 `openspec validate --all --strict`
- [ ] 9.6 `mkdocs build --strict`
- [ ] 9.7 Write `evidence.md` — the commands and pasted result tails of one final
      fresh run after the last code edit, plus the commit SHA.
- [ ] 9.8 File the separate issue for the `GitFacadeConfiguration` fetch-audit ref
      mislabelling, referenced as out of scope in the proposal.
