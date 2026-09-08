# Tasks: external-source-closure

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_INGEST_0030 (the closure as an immutable domain object) and GW_APPROVAL_0013
      (approval requires a complete closure) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_INGEST_0030 and SVC_GW_APPROVAL_0013 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`
- [x] 1.3 Spec deltas: `marketplace-ingestion` (GW_INGEST_0030), `snapshot-approval`
      (GW_APPROVAL_0013)

## 2. Tests (red before green)

- [x] 2.1 `SnapshotClosureDigestTests` (`@SVCs({"SVC_GW_INGEST_0030"})`), pure unit:
      digest is 64 hex, identical for identical closures regardless of member
      order, and differs when the upstream commit, the transformer version or
      any member field differs; the empty closure has a digest of its own
- [x] 2.2 `SnapshotClosureTests` (`@SVCs({"SVC_GW_INGEST_0030"})`), the enabled Spring
      context against the in-process forge: the recorded closure and its member
      fields after a resolved ingestion; `upstream_sha` on composite, local-only
      and rejected snapshots; no closure row for the latter two; the provenance
      response; the policy facts; the blast-radius query; cascade on purge
- [x] 2.3 `ClosureCompletenessTests` (`@SVCs({"SVC_GW_APPROVAL_0013"})`): each tampering
      of the persisted state refuses with `ClosureIncompleteException`, is on the
      ledger, leaves the snapshot held and publishes nothing — including under an
      administrative override request; an untouched composite and a local-only
      snapshot still approve
- [x] 2.4 Existing suites unmodified: `ApprovalTests` (SVC_GW_APPROVAL_0002, SVC_GW_INGEST_0004),
      `ExternalSourceResolutionTests`, `IngestionTests`, `RetentionTests`

## 3. Schema and domain object

- [x] 3.1 `V1__init.sql`: `snapshots.upstream_sha`, `snapshot_closures`,
      `snapshot_closure_members`, the digest index and the `(clone_url,
      resolved_sha)` index
- [x] 3.2 `SnapshotClosure` value object with `Member` and `digest()`;
      `@Requirements({"GW_INGEST_0030"})`
- [x] 3.3 `SnapshotClosureRepository`: `record` (called inside the snapshot
      insert's transaction), `findBySnapshot`, `snapshotsContaining(cloneUrl,
      resolvedSha)`; `@Requirements({"GW_INGEST_0030"})`
- [x] 3.4 `Snapshot` gains `upstreamSha`; `SnapshotRepository.create` takes the
      upstream commit and an optional closure and writes both transactionally

## 4. Ingestion

- [x] 4.1 `ExternalSourceResolver.Resolved` carries the declared source, the
      source type and the measurement; `IngestionService.serve` builds the
      `SnapshotClosure` and `ingestLocked` records it with the snapshot;
      `@Requirements({"GW_INGEST_0030"})`

## 5. Approval

- [x] 5.1 `ClosureCompletenessGate` and `ClosureIncompleteException`; the gate
      runs first inside `doApprove`'s decidable branch, on every path, and its
      refusal is written to the ledger; `@Requirements({"GW_APPROVAL_0013"})`
- [x] 5.2 `AdminController` maps the exception to 409 with the discrepancies

## 6. Surfaces

- [x] 6.1 `ApprovalService.Provenance`: `sha`, `upstreamSha` from the column,
      `closure`; `@Requirements({"GW_INGEST_0004", "GW_INGEST_0030"})`
- [x] 6.2 `SnapshotFactsService`: `snapshot.upstreamSha`,
      `snapshot.externalSources`, per-plugin `origin`, `upstreamUrl`,
      `resolvedSha`; `@Requirements({"GW_APPROVAL_0007", "GW_INGEST_0030"})`
- [x] 6.3 `openapi.json` and `types.gen.ts` regenerated; provenance dialog in
      `marketplaces.tsx` lists the served commit and the closure

## 7. Documentation (same PR)

- [x] 7.1 `concepts/snapshots-and-ledger.md`: the closure record
- [x] 7.2 `reference/api/marketplaces.md`: provenance shape, the 409 on approve
- [x] 7.3 `guides/approving-snapshots.md`: what the closure shows a reviewer and
      what the completeness refusal means
- [x] 7.4 `guides/policy-rules.md`: the new facts
- [x] 7.5 `reference/portal.md`, `concepts/glossary.md`, `architecture.md`
- [x] 7.6 ADR 0011: the increment row moves to shipped with its ids; the
      `upstream_sha` deviation is closed

## 8. Gates and evidence (old-coder gauntlet)

- [x] 8.1 `./mvnw clean verify`
- [x] 8.2 `(cd src/main/frontend && pnpm test:stories)` and `pnpm e2e`
- [x] 8.3 `reqstool status local -p docs/reqstool` ends `PASS`
- [x] 8.4 `openspec validate --all --strict`
- [x] 8.5 `mkdocs build --strict`
- [x] 8.6 `mutants.sh` and `evidence.md` with the commit SHA of one final fresh run
