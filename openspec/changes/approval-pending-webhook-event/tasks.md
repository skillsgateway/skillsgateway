# Tasks: approval-pending-webhook-event

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0159 (the approval-pending lifecycle event) and GW_0160 (its
      content-free payload) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0159 and SVC_GW_0160 to
      `docs/reqstool/software_verification_cases.yml`

## 2. Failing tests first (SVC_GW_0159, SVC_GW_0160)

- [x] 2.1 In `WebhookTests`, add the event test: a subscriber filtering only
      `snapshot.approval_pending` receives exactly one delivery for an ingested
      held snapshot, with the vetting summary in the payload; a subscriber
      filtering another event receives none; the registry offers the new name
      (SVC_GW_0159)
- [x] 2.2 In `WebhookTests`, add the adversarial payload test: the payload of a
      snapshot with real blocking findings carries no finding message, rule id,
      location or file name — only counts, connector names and identifiers; and
      the exact top-level and nested key sets are asserted, so a removed or
      renamed field fails the build (SVC_GW_0160)
- [x] 2.3 Add the estate test: a subscriber declared in
      `skills-gateway.estate.webhooks` may name the new event in its filter, and
      a typo is still a reconciliation failure (SVC_GW_0159)
- [x] 2.4 Add the negative-state test: a re-vetting run against an **approved**
      snapshot emits `snapshot.revet_violation`/`snapshot.vetted` but never
      `snapshot.approval_pending` (SVC_GW_0159)
- [x] 2.5 Run the new tests and record them failing before any production code
      changes

## 3. Implementation

- [x] 3.1 `WebhookEvent`: add `SNAPSHOT_APPROVAL_PENDING` with its rationale
      comment and append it to `ALL`
- [x] 3.2 `WebhookService`: add `ApprovalPendingPayload` and `VettingSummary`
      records with `@Schema(requiredMode = REQUIRED)` on every field, factor the
      fan-out into one private helper, and add `emitApprovalPending(...)` with
      `@Requirements({"GW_0159", "GW_0160"})`
- [x] 3.3 `VettingService`: inject `WaiverService`, and after the existing
      `SNAPSHOT_VETTED` emit, emit the new event when the snapshot is held —
      annotated `@Requirements({"GW_0159"})`
- [x] 3.4 Update the two test-side `new VettingService(...)` call sites for the
      new constructor argument

## 4. Documentation (same PR)

- [x] 4.1 `docs/manual/guides/lifecycle-webhooks.md`: the new row in the event
      table, a payload example with the `vetting` object, the field table, the
      "announces, does not disclose" note, and the ordering caveat that
      `snapshot.approval_pending` can arrive before `snapshot.ingested`
- [x] 4.2 Cross-link the approve/reject round-trip an external review system
      drives, as the issue asks

## 5. Gates and archive

- [x] 5.1 `./mvnw clean verify`
- [x] 5.2 `(cd src/main/frontend && pnpm test:stories)`
- [x] 5.3 `(cd src/main/frontend && pnpm e2e)`
- [x] 5.4 `reqstool status local -p docs/reqstool` ends `PASS`
- [x] 5.5 `openspec validate --all --strict`
- [x] 5.6 `mkdocs build --strict`
- [x] 5.7 Write `evidence.md` with the pasted tails of one final fresh run and
      the commit SHA
- [x] 5.8 Archive the change as the final commit
