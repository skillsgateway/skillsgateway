# Tasks: four-eyes-approval

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0096 (approval separation of duties: conflict detection for
      ingestion actor, registrant, waiver authors; enforce refuses fail-closed)
      and GW_0097 (warn/enforce mode configuration, default warn; conflicts
      recorded on the ledger in both modes) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0096 and SVC_GW_0097 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Failing tests first (old-coder: prove they fail)

- [x] 2.1 `FourEyesTests` (extends `AbstractGatewayTest`), annotated
      `@SVCs({"SVC_GW_0096"})`: enforce mode — approval by the ingestion actor
      is refused, by the marketplace registrant is refused, and by the author
      of an applied waiver is refused; in every refusal the snapshot remains
      `HELD`, nothing is published (`storage.publishedIfServing` empty), and
      `decided_by` stays NULL; a different reviewer then approves successfully;
      `scheduler`/`webhook`/NULL actors never conflict
- [x] 2.2 Warn-mode tests, annotated `@SVCs({"SVC_GW_0097"})`: conflicted
      approval proceeds, snapshot published, and a `four-eyes-conflict` ledger
      row exists naming the conflict; non-conflicted approval writes no such
      row; default mode is warn
- [x] 2.3 HTTP-layer negative test (MockMvc, `RoleEnforcementTests` pattern):
      enforce-mode approval returns 409 with problem `conflicts` property;
      confirm all new tests FAIL before any implementation (record the run)

## 3. Persistence and actor capture

- [x] 3.1 Flyway `V2__four_eyes_actors.sql`: `marketplaces.registered_by TEXT`,
      `snapshots.ingested_by TEXT` (nullable)
- [x] 3.2 Thread the registrant through marketplace registration
      (`AdminController` → service → repository) and the ingestion actor
      through snapshot creation (human principal; `SyncService` constants for
      scheduler/webhook); expose `registeredBy`/`ingestedBy` in the API views

## 4. Approval rule

- [x] 4.1 `SkillsGatewayProperties`: `Approval(FourEyes fourEyes)` /
      `FourEyesMode { WARN, ENFORCE }`, default WARN, `enforcing()`;
      `@Requirements({"GW_0097"})`
- [x] 4.2 `FourEyesConflictException` with `Conflict(role, principal, waiverId)`
      records; conflict detection in `ApprovalService.doApprove` after waiver
      evaluation, before `decide(...)`; `@Requirements({"GW_0096"})`; extend
      `Approved` with detected conflicts for warn mode
- [x] 4.3 `AdminController`: 409 `@ExceptionHandler` ("Four-eyes rule refused
      this approval", `conflicts` property); warn-mode `four-eyes-conflict`
      ledger event next to `snapshot-approved`

## 5. Portal

- [x] 5.1 Expose `ingestedBy` in the snapshot row type and `registeredBy` in
      the marketplace type (`src/main/frontend/src/api/types`), JSDoc
      `@Requirements GW_0096`
- [x] 5.2 `ApproveDialog`: compare `/api/me` username against the snapshot's
      conflict identities — enforce: disable Approve with explanation (existing
      `blocked` pattern); warn: non-blocking warning copy; 409 → toast
- [x] 5.3 Playwright e2e: enforce-mode refusal visible in a real browser
      (JSDoc `@SVCs SVC_GW_0096`)

## 6. Documentation (same PR)

- [x] 6.1 `docs/manual/reference/configuration.md`: `skills-gateway.approval.
      four-eyes.mode` section + index row (revet section as the model; state
      the two-principals prerequisite for enforce)
- [x] 6.2 `docs/manual/guides/approving-snapshots.md`: conflict refusal/warning
      behavior; `docs/manual/concepts/trust-boundaries.md`: the rule as part of
      the approval boundary; `docs/manual/reference/api/marketplaces.md`: new
      fields + 409
- [x] 6.3 `docs/manual/architecture.md`: implemented-today note (GW_0096–0084)

## 7. Gates and evidence (old-coder gauntlet)

- [x] 7.1 `./mvnw clean verify`
- [x] 7.2 `(cd src/main/frontend && pnpm e2e)`
- [x] 7.3 `reqstool status local -p docs/reqstool` ends PASS;
      `openspec validate --all --strict`; `mkdocs build --strict`
- [x] 7.4 `evidence.md`: fresh final run of all gates after the last edit,
      pasted tails + commit SHA

---

Three items were completed differently from how they are worded above — the
migration folded into `V1__init.sql` (repository convention), the portal
pre-check answered by a server endpoint rather than by comparing `/api/me` in
the browser, and the real-browser e2e covering the warn-mode notice with the
enforced refusal verified over HTTP. See *Implementation notes — where the
design met the code* in `design.md`.
