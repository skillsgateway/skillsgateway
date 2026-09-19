# Tasks: administrative-revocation

## 1. Requirements

- [x] 1.1 Add `GW_APPROVAL_0015` (administrative revocation), `GW_APPROVAL_0016`
      (the served content afterwards is a stated choice), `GW_APPROVAL_0017`
      (approved again only on a recorded reversal by a second administrator) and
      `GW_RETENTION_0008` (an administrative revocation is never erased by
      retention) to `docs/reqstool/requirements.yml`.
- [x] 1.2 State the four-eyes asymmetry with `GW_APPROVAL_0010` in
      `GW_APPROVAL_0015`'s rationale — revoking takes one identity, reversing
      takes two.
- [x] 1.3 Add the four matching SVCs.

## 2. Schema

- [x] 2.1 Add the revocation-kind column to `snapshots` in `V1__init.sql`,
      defaulting to the re-vetting kind so existing revoked rows read correctly,
      with a comment saying why the two kinds are distinguished — they lift
      differently.
- [x] 2.2 Add the marker table or column for a snapshot approved over a reversed
      administrative revocation, mirroring `snapshot_vetting_overrides`, since
      `decide` clears `revoked_at`, `revoked_by` and `violation`.

## 3. Repository

- [x] 3.1 `SnapshotRepository.revoke` takes the kind.
- [x] 3.2 A lookup answering "was this `(marketplace, sha)` administratively
      revoked?" returning the reason, actor and time — not a boolean, because
      the refusal must name them.
- [x] 3.3 `purge` refuses an administratively revoked row; soft delete is
      unaffected.

## 4. Revocation

- [x] 4.1 `RevocationService` beside `ApprovalService`.
- [x] 4.2 Conditional state transition first, as `RevetService.quarantine` does.
- [x] 4.3 Validate the served-content choice **before** the transition; refuse a
      missing choice and a rollback with no earlier approved snapshot.
- [x] 4.4 Unpublish, then ledger what the marketplace now serves.
- [x] 4.5 On rollback, publish the earlier snapshot and ledger it as a
      publication distinct from the revocation.
- [x] 4.6 Fail loudly and do not retry when the record and the wire disagree.
- [x] 4.7 Emit `marketplace.snapshot.revoked`.
- [x] 4.8 Comment the one-identity-to-revoke, two-to-reverse asymmetry.
- [x] 4.9 `RevetService.quarantine` passes the re-vetting kind.

## 5. The approval gate and the reversal

- [x] 5.1 `doApprove` refuses an administratively revoked snapshot, after the
      closure check and before the other gates, naming the revocation.
- [x] 5.2 Extend `ApprovalOverride` with the reversal kind and its reason.
- [x] 5.3 Refuse a reversal by the revoking identity, **unconditionally** — not
      through `FourEyesGate`, whose default mode records and proceeds.
- [x] 5.4 Refuse a reversal with no reason, an empty reason, or a non-admin
      caller.
- [x] 5.5 Every other approval gate runs unchanged on a reversal.
- [x] 5.6 Write the marker only once publication lands, as the vetting override
      does.
- [x] 5.7 Ledger the reversal naming the revocation it reverses.
- [x] 5.8 Confirm a re-vetting revocation still lifts by clearing the finding,
      with `SVC_GW_VETTING_0013` and `SVC_GW_APPROVAL_0013` untouched.

## 6. API

- [x] 6.1 `POST /api/snapshots/{id}/revoke` — admin-only, mandatory non-empty
      reason, required served-content choice with no default.
- [x] 6.2 The reversal rides the existing approve request; no new endpoint.
- [x] 6.3 Distinguishable refusals for each cause.
- [x] 6.4 Machine API scopes consistent with the existing approval scopes.
- [x] 6.5 Confirm the OpenAPI contract check sees it as additive.

## 7. Tests — negative first (`old-coder` discipline)

- [x] 7.1 Prove each test fails before its implementation exists.
- [x] 7.2 Revoke refused: non-admin; missing reason; empty reason; snapshot not
      approved.
- [x] 7.3 Revoke takes effect with the chain clearing the snapshot and with
      re-vetting in record-only mode.
- [x] 7.4 Two concurrent revocations: one succeeds, one is refused, unpublished
      exactly once.
- [x] 7.5 Rollback with no earlier approved snapshot: refused **and the snapshot
      is still approved**, asserted on state.
- [x] 7.6 Each served-content outcome writes its own ledger entries; a rollback
      is recorded as a publication.
- [x] 7.7 Revoking a snapshot that is not the served one leaves the served set
      untouched.
- [x] 7.8 Ordinary approval of an administratively revoked snapshot is refused
      and the refusal names the reason, actor and time.
- [x] 7.9 The revoker cannot reverse their own revocation; a second
      administrator can; the marker is present afterwards.
- [x] 7.10 A waiver does not lift an administrative revocation.
- [x] 7.11 Retention reclaims an administratively revoked snapshot's content but
      refuses to purge its record, while a re-vetting-revoked record purges.
- [x] 7.12 Reconciling a converged declarative estate revokes nothing and writes
      no ledger entry.

## 8. Documentation

- [x] 8.1 `docs/manual/reference/api/marketplaces.md` — the endpoint, its body,
      every refusal.
- [x] 8.2 `docs/manual/guides/approving-snapshots.md` — withdrawing, choosing
      what is served afterwards, and how a withdrawal is reversed.
- [x] 8.3 `docs/manual/reference/retention.md` — the administrative-revocation
      guard.
- [x] 8.4 State that revocation stops the gateway serving the content and does
      not reach clients that already hold it, linking
      [#427](https://github.com/skillsgateway/skillsgateway/issues/427).
- [x] 8.5 Any new page placed by its Diátaxis type and added to `nav:`.

## 9. Gates and archive

- [x] 9.1 `./mvnw clean verify`
- [x] 9.2 `(cd src/main/frontend && pnpm test:stories)`
- [x] 9.3 `(cd src/main/frontend && pnpm e2e)`
- [x] 9.4 `reqstool status local -p docs/reqstool` — must end `PASS`
- [x] 9.5 `openspec validate --all --strict`
- [x] 9.6 `mkdocs build --strict`
- [x] 9.7 `evidence.md` from one final fresh run after the last edit.
- [ ] 9.8 PR with an **Evidence** section; archive as the final commit.
