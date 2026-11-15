# Tasks: marketplace-auto-approval

Trust-boundary change (`ApprovalService`, the approval evidence, re-vetting):
follow `.claude/skills/old-coder`. Watch every SVC test fail before its
implementation, include adversarial cases, and run mutation testing over the
predicate. The portal work is the Approval posture card (design D15).

Annotation placement follows the reqstool conventions:

- `@Requirements` goes on the implementing method, and `@SVCs` on the test
  method, for each child `GW_APPROVAL_0044.N`.
- The parent `@SVCs SVC_GW_APPROVAL_0044` goes at class level on the end-to-end
  test class, `AutoApprovalTests`.

## 1. Requirements and decision record

- [x] 1.1 Add the requirements and SVCs to `docs/reqstool/` (the system directory, which is the only module, so there are no subproject filters to update):
  - **`requirements.yml`:** parent `GW_APPROVAL_0044` and its children `GW_APPROVAL_0044.1` to `.15`, each child with `references: requirement_ids: ["GW_APPROVAL_0044"]`, plus `GW_ESTATE_0006`. Each entry has a `title`, `significance: shall`, a `description`, a `rationale`, `categories` (`security` for every entry except `.12`, `.15` and `GW_ESTATE_0006`, which are `functional-suitability`) and `revision: 0.1.0`, inserted after `GW_APPROVAL_0038` and `GW_ESTATE_0005`.
  - **`software_verification_cases.yml`:** `SVC_GW_APPROVAL_0044`, `SVC_GW_APPROVAL_0044.1` to `.15` and `SVC_GW_ESTATE_0006`, each mirroring its requirement, as `automated-test` with GIVEN/WHEN/THEN.

  Use the titles in proposal.md. Verify with `reqstool status local -p docs/reqstool` after a `clean` build.
- [x] 1.2 Write `docs/decisions/0023-automatic-approval-of-internal-marketplaces.md`. It supersedes ADR 0006's deny-only position for opted-in marketplaces, and states the accepted residual risk (design D7), the reconciler's four-eyes exemption (D4), and why re-vetting enforces only for auto-approved snapshots (D12). Amend ADR 0006 (superseded in part) and ADR 0021 ("What would reopen this": decided, with the findings predicate and no tiers). Verify with `mkdocs build --strict`.

## 2. Preparation refactors (no behaviour change)

- [x] 2.1 Pin the human approval's evidence: a test that approves through `POST /api/v1/snapshots/{id}/approve` and asserts the exact ledger entries (`waiver-applied`, `snapshot-approved`, override, superseded chain, warn-mode `four-eyes-conflict`) and the `SNAPSHOT_APPROVED` webhook. It must pass before and after 2.2, and becomes the human half of `SVC_GW_APPROVAL_0044.10`.
- [x] 2.2 Move the post-approval block of `AdminController.approve` into `ApprovalRecorder` (design D9). Verify with 2.1 and the existing approval tests unchanged.
- [x] 2.3 Extract `UpstreamCredentials.Target` into a shared `UrlPrefix` (design D2). Verify with the existing credential tests unchanged.

## 3. Schema, configuration and classification

- [ ] 3.1 `V1__init.sql`:
  - `marketplaces.approval_posture`, `approval_set_by`, `approval_reason` and `approval_expires_at`;
  - `snapshots.auto_approval_skip_reason`;
  - the `auto_approval_overrides` table, with its 90-day `CHECK` and a partial unique index for the active override (design D1, D3, D9).
- [ ] 3.2 Add `skills-gateway.approval.auto.mode` (`off` | `shadow` | `on`, default `off`), `skills-gateway.approval.auto.internal-prefixes` and `skills-gateway.approval.auto.required-vetters` (unset by default, meaning every vetter). Startup refuses an unparseable prefix, a host-wide prefix on a multi-tenant host or on the `github-base-url` host, an empty `required-vetters` list, and a `required-vetters` name that is not a configured vetter. Raise `ConfigSurfaceBudgetTests` by the six leaves added (these three and the three estate keys of 7.1), and argue each in the PR.
- [ ] 3.3 Internal-source classification, with tests written first (`@SVCs SVC_GW_APPROVAL_0044.2`, `@Requirements GW_APPROVAL_0044.2`). An exact organisation matches, and a hosted marketplace is internal. These do not match:
  - a lookalike organisation;
  - a different scheme, host or port;
  - userinfo, encoded separators or dot segments;
  - a case-shifted path segment.

## 4. Posture, override and four-eyes

- [ ] 4.1 Posture (`@SVCs SVC_GW_APPROVAL_0044.1`, `@Requirements GW_APPROVAL_0044.1`):
  - it defaults to `manual`;
  - `auto` needs a reason and an expiry of at most 90 days (the posture's own `MAX_LIFETIME` constant), and a lapsed one is effectively manual;
  - only an administrator sets it, including at registration;
  - `auto` without an internal basis is refused at registration.
- [ ] 4.2 Override (`@SVCs SVC_GW_APPROVAL_0044.3`, `@Requirements GW_APPROVAL_0044.3`):
  - it needs a reason and an expiry of at most 90 days (the override's own `MAX_LIFETIME` constant, matching the table's `CHECK`), and caps the posture's expiry;
  - expiry or revocation makes the basis `none`, recorded once.
- [ ] 4.3 Four-eyes on the grant (`@SVCs SVC_GW_APPROVAL_0044.4`, `@Requirements GW_APPROVAL_0044.4`, design D4):
  - under `warn`, a grant by the registering administrator is recorded as a conflict;
  - under `enforce`, it stays `awaiting-second-administrator` until a different administrator confirms;
  - the estate reconciler is exempt, and approvals' four-eyes behaviour is unchanged.
- [ ] 4.4 The posture endpoints and their ledger events (design D14).

## 5. Eligibility (`AutoApprovalService`)

- [ ] 5.1 Newest only (`@SVCs SVC_GW_APPROVAL_0044.5`, `@Requirements GW_APPROVAL_0044.5`): an older held snapshot attempted after a newer one exists stays held, so the served tip never moves backwards. A revoked or deleted snapshot is never a candidate.
- [ ] 5.2 No external source (`@SVCs SVC_GW_APPROVAL_0044.6`, `@Requirements GW_APPROVAL_0044.6`): one closure member, whatever its URL, including an internal one, keeps the snapshot held.
- [ ] 5.3 Vetting (`@SVCs SVC_GW_APPROVAL_0044.7`, `@Requirements GW_APPROVAL_0044.7`):
  - **stays held:**
    - an `UNDETERMINED` or `SUPERSEDED` chain;
    - any `NOT_REACHED`, `ERROR` or `PENDING` verdict, whatever `required-vetters` says;
    - with `required-vetters` unset, any `DISABLED` verdict. Include the adversarial case: every vetter but `secret-scan` disabled, and `secret-scan` passing;
    - with `required-vetters: [secret-scan]`, `secret-scan` disabled;
    - an uncovered high finding;
    - a high finding waived but absent from the baseline in that blob;
    - `hook-config-unreadable`, `hook-target-unscanned` or `too-many-plugin-roots` at any severity;
  - **approved:**
    - medium findings only;
    - a high finding waived and present in the baseline in that blob;
    - with `required-vetters: [secret-scan]`, another vetter disabled and `secret-scan` passing, with the disabled vetter named in the `snapshot-auto-approved` entry.
- [ ] 5.4 Executable surface (`@SVCs SVC_GW_APPROVAL_0044.8`, `@Requirements GW_APPROVAL_0044.8`):
  - **stays held:**
    - a changed hook, hook module, MCP or LSP declaration, `allowed-tools` frontmatter, or agent `tools`;
    - an added or modified non-inert path: an unknown extension, mode `100755`, a mode-only change, a symlink, a gitlink, a shebang, a dependency manifest, or a launch target;
    - an oversize component file, or plugins past `MAX_PLUGINS`;
    - a first snapshot with any surface;
  - **approved:** a Markdown-only change, a deletion, and a first snapshot that is entirely inert.
- [ ] 5.5 Gates (`@SVCs SVC_GW_APPROVAL_0044.9`, `@Requirements GW_APPROVAL_0044.9`):
  - a policy denial, a name collision or an open closure leaves the snapshot held;
  - a snapshot that is too young is held, and approved on a later re-vetting tick once of age;
  - when a person decides first, the automatic attempt records a skip.
- [ ] 5.6 Evidence (`@SVCs SVC_GW_APPROVAL_0044.10`, `@Requirements GW_APPROVAL_0044.10`): the automatic approval writes the same entries and webhook as 2.1, through `ApprovalRecorder`.
- [ ] 5.7 Ledger (`@SVCs SVC_GW_APPROVAL_0044.11`, `@Requirements GW_APPROVAL_0044.11`):
  - `snapshot-auto-approved` is typed `system`, with the basis, grantor, chain, baseline SHA, worst severity and waiver ids;
  - a skip is written only when `auto_approval_skip_reason` changes.
- [ ] 5.8 Mode (`@SVCs SVC_GW_APPROVAL_0044.12`, `@Requirements GW_APPROVAL_0044.12`):
  - under `off`, nothing is evaluated for publication;
  - under `shadow`, every check runs, `snapshot-auto-approval-would-approve` is written, and nothing is published;
  - under `on`, the snapshot is published;
  - with `revet.enabled=false`, the attempt skips with `revet-disabled`.
- [ ] 5.9 Triggers (design D10): after `IngestionService.ingest` returns and after `refreshHeld`, as direct calls; after a waiver is created and after a posture is set; and one bounded step on the `RevetScheduler` tick. `GET /api/v1/snapshots/{id}/auto-approval` reports the same evaluation, from the same code.
- [ ] 5.10 The end-to-end class `AutoApprovalTests` (`@SVCs SVC_GW_APPROVAL_0044` at class level, `@Requirements GW_APPROVAL_0044` on `AutoApprovalService.attempt`):
  - an internal marketplace in `on` mode ingests a Markdown-only update and serves it with no person;
  - the same update with an external source added stays held.
- [ ] 5.11 Run `mutants.sh` over the predicate's conditions, and record the survivors with a rationale in `evidence.md`.

## 6. Re-vetting and withdrawal

- [ ] 6.1 Re-vetting (`@SVCs SVC_GW_APPROVAL_0044.13`, `@Requirements GW_APPROVAL_0044.13`): with global `revet.mode=warn`, a violation on an auto-approved snapshot revokes and unpublishes it, while the same violation on a human-approved snapshot only records.
- [ ] 6.2 `POST /api/v1/approval/auto/withdraw` (`@SVCs SVC_GW_APPROVAL_0044.14`, `@Requirements GW_APPROVAL_0044.14`):
  - it is admin-only;
  - it revokes exactly the live auto-approved snapshots since `since`, newest first, through `RevocationService`;
  - human-approved snapshots are untouched, and the response lists what was withdrawn.

## 7. Estate

- [ ] 7.1 `skills-gateway.estate.marketplaces[].approval.{posture, reason, expires-at}` (`@SVCs SVC_GW_ESTATE_0006`, `@Requirements GW_ESTATE_0006`):
  - the reconciler applies it as `config-reconciler`;
  - `auto` with no internal basis, or with a missing or too-distant expiry, is a reconciliation failure;
  - an override is never implied.

## 8. Portal

- [ ] 8.1 Regenerate `src/api/types.gen.ts` with `pnpm gen:api-types` from the committed `openapi.json` after group 4, so the card uses the generated types.
- [ ] 8.2 The Approval posture card on `MarketplaceSettingsPage` (`@Requirements GW_APPROVAL_0044.15` in JSDoc, design D15): it shows the posture, basis, grantor, reason, expiry, active override and deployment mode to anyone who can see Settings; an administrator sets or renews the posture, creates the override for a marketplace with no internal basis, and revokes the override behind a confirmation. Server refusals are shown as returned.
- [ ] 8.3 Storybook stories for each state: manual; auto, internal; auto by override; awaiting a second administrator; lapsed; non-administrator view; a refused save. Verify with `pnpm test:stories`.
- [ ] 8.4 An e2e test in `e2e/portal.spec.ts` (`@SVCs SVC_GW_APPROVAL_0044.15`): an administrator sets `auto` with an override on a marketplace outside the prefixes, sees the basis `override`, revokes it, and sees the basis `none`. Verify with `pnpm e2e`.
- [ ] 8.5 Run `/impeccable audit` and `/impeccable harden` on the Settings page, and capture before/after screenshots for the PR body (`.claude/skills/ui-screenshots`).

## 9. Documentation

- [ ] 9.1 Update the docs pages:
  - `guides/approving-snapshots.md`: an Automatic approval section covering shadow mode, the checks, and withdrawal;
  - `guides/registering-a-marketplace.md`;
  - the declarative estate guide;
  - `reference/configuration.md`, including `required-vetters`;
  - `reference/api/marketplaces.md`, and the snapshots and approval API pages;
  - `reference/portal.md`: the Approval posture card;
  - `concepts/trust-boundaries.md`;
  - `capability-map.md`: the Approval row.

  Verify with `mkdocs build --strict`.

## 10. Gates and evidence

- [ ] 10.1 Run every AGENTS.md gate fresh after the last code edit: `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`, `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`, `mkdocs build --strict`. Record the commands, result tails and commit SHA in `evidence.md`.
- [ ] 10.2 `/opsx:archive` as the final commit of the PR.
