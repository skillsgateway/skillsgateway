# Tasks: administrative-revocation

## 1. Requirements

- [ ] 1.1 Add `GW_APPROVAL_0015` (administrative revocation of an approved
      snapshot), `GW_APPROVAL_0016` (the served content afterwards is a stated
      choice), `GW_APPROVAL_0017` (a revoked commit is refused at approval) and
      `GW_INGEST_0032` (a revoked commit is refused at ingestion) to
      `docs/reqstool/requirements.yml`, each with its rationale.
- [ ] 1.2 In `GW_APPROVAL_0015`'s rationale, state the four-eyes asymmetry with
      `GW_APPROVAL_0002` explicitly — publishing can do harm, withdrawing cannot
      — so it reads as a decision rather than an omission.
- [ ] 1.3 Add the four matching SVCs to `software_verification_cases.yml`.

## 2. The revoked-commit lookup

- [ ] 2.1 `SnapshotRepository`: a method answering "is there a revoked snapshot
      for this `(marketplace, sha)`?", returning the revocation's reason, actor
      and time — not a boolean, because both refusals must name it.
- [ ] 2.2 Confirm the query is served by an existing index; add one only if it
      is not, and say why in the migration comment.

## 3. Revocation

- [ ] 3.1 `RevocationService` beside `ApprovalService`, taking `GitStorage`, the
      snapshot repository, the audit logger and the webhook emitter.
- [ ] 3.2 The conditional state transition first, exactly as
      `RevetService.quarantine` does it, so a double-submit and a concurrent
      re-vet cannot both revoke or both unpublish.
- [ ] 3.3 Validate the served-content choice **before** the transition: reject a
      missing choice, and reject a rollback with no earlier approved snapshot,
      leaving the snapshot approved in both cases.
- [ ] 3.4 Unpublish, then ledger what the marketplace now serves.
- [ ] 3.5 On rollback, publish the earlier approved snapshot and ledger it as a
      publication distinct from the revocation.
- [ ] 3.6 Fail loudly and do not retry when the record and the wire disagree,
      inheriting `RevetService`'s reasoning and its log shape.
- [ ] 3.7 Emit `marketplace.snapshot.revoked`.
- [ ] 3.8 Comment the no-four-eyes decision at the service, so it is not "fixed"
      later.

## 4. The gates

- [ ] 4.1 `ApprovalService.doApprove`: refuse a revoked commit, before any state
      transition and before publication, with the refusal naming the revocation.
      Place it first among the gates.
- [ ] 4.2 Ingestion: refuse to create a held snapshot for a revoked commit, and
      record the refusal on the ledger naming the revocation.
- [ ] 4.3 Confirm the scheduled sweep takes the same path, so a marketplace
      whose upstream has not moved does not reappear for approval.

## 5. API

- [ ] 5.1 `POST /api/snapshots/{id}/revoke` — admin-only, mandatory non-empty
      reason, required served-content choice with no default.
- [ ] 5.2 Status codes: refusals distinguishable from each other (not an
      administrator, snapshot not approved, no reason, no choice stated,
      rollback impossible).
- [ ] 5.3 Machine API scope for it, consistent with the existing approval scopes.
- [ ] 5.4 Confirm the OpenAPI contract check sees it as additive.

## 6. Tests — negative first (`old-coder` discipline)

- [ ] 6.1 Prove each test fails before its implementation exists.
- [ ] 6.2 Refused: non-admin; missing reason; empty reason; snapshot not
      approved.
- [ ] 6.3 Takes effect anyway: vetting chain currently clearing the snapshot;
      re-vetting mode set to record-only.
- [ ] 6.4 Two concurrent revocations of one snapshot — one succeeds, one is
      refused, the content is unpublished exactly once.
- [ ] 6.5 Rollback with no earlier approved snapshot: refused **and the snapshot
      is still approved afterwards**, asserted on state, not only status.
- [ ] 6.6 Serve-nothing and rollback outcomes each produce their own ledger
      entries, and a rollback is recorded as a publication.
- [ ] 6.7 Revoking a snapshot that is not the served one leaves the served set
      untouched.
- [ ] 6.8 A revoked commit re-ingested by the scheduled sweep creates no held
      snapshot and writes one ledger entry naming the revocation.
- [ ] 6.9 A held snapshot that predates the revocation cannot be approved, and
      the refusal carries the reason, actor and time.
- [ ] 6.10 Reconciling a converged declarative estate revokes nothing and writes
      no ledger entry — the #65 obligation does not extend to acts.

## 7. Documentation

- [ ] 7.1 `docs/manual/reference/api/marketplaces.md`: the endpoint, its request
      body, every refusal and what each means.
- [ ] 7.2 `docs/manual/guides/approving-snapshots.md`: the withdrawal half of
      the lifecycle — when to revoke, how to choose what is served afterwards,
      and that there is no un-revoke.
- [ ] 7.3 State plainly that revocation stops the gateway serving the content and
      does **not** reach clients that already hold it, linking
      [#427](https://github.com/skillsgateway/skillsgateway/issues/427).
- [ ] 7.4 Place any new page by its Diátaxis type and add it to `nav:`.

## 8. Gates and archive

- [ ] 8.1 `./mvnw clean verify`
- [ ] 8.2 `(cd src/main/frontend && pnpm test:stories)`
- [ ] 8.3 `(cd src/main/frontend && pnpm e2e)`
- [ ] 8.4 `reqstool status local -p docs/reqstool` — must end `PASS`
- [ ] 8.5 `openspec validate --all --strict`
- [ ] 8.6 `mkdocs build --strict`
- [ ] 8.7 `evidence.md` from one final fresh run after the last edit, with the
      commit SHA.
- [ ] 8.8 PR with an **Evidence** section; archive the change as the final
      commit.
