# Tasks: corpus-aware-vetting

**Blocked on ADR 0014 being accepted.** Nothing below is implemented. If the
owner takes a different option from ADR 0014's "Decisions to confirm", most of
sections 3–6 change shape and this list should be regenerated rather than
patched.

## 0. Prerequisite

- [ ] 0.1 ADR 0014 — Corpus questions are approval-gate preconditions, not
      vetting connectors — accepted, with the four "Decisions to confirm"
      answered (waivability, confusable fold aggressiveness, un-revocation,
      and whether a corpus-aware connector is wanted after all)

## 1. Requirements (SSOT first)

Ids GW_0194–GW_0198 and their SVCs are **reserved but not yet written** — they
are deliberately absent from `docs/reqstool/` so the reqstool gate stays green
while this proposal is unimplemented. Task 1.1 is what puts them there.

- [ ] 1.1 Add to `docs/reqstool/requirements.yml`:
      GW_0194 — Persisted snapshot facts;
      GW_0195 — Approval is refused on a normalised plugin-name collision with
      the approved estate;
      GW_0196 — A collision refusal is acceptable only by a scoped, expiring
      waiver;
      GW_0197 — Collision refusals and their acceptances are audit-logged and
      shown to the reviewer;
      GW_0198 — Corpus state is never an input to a vetting chain run
- [ ] 1.2 Add SVC_GW_0194, SVC_GW_0195.1–.3, SVC_GW_0196.1–.2, SVC_GW_0197 and
      SVC_GW_0198 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Schema (SVC_GW_0194)

- [ ] 2.1 `V2__snapshot_facts.sql`: `snapshot_facts` (`snapshot_id BIGINT NOT
      NULL UNIQUE REFERENCES snapshots(id) ON DELETE CASCADE`, `facts JSONB`,
      `builder_version TEXT NOT NULL`, `built_at TIMESTAMPTZ NOT NULL`,
      `unavailable_reason TEXT`, `CHECK (facts IS NOT NULL OR unavailable_reason
      IS NOT NULL)`)
- [ ] 2.2 Same migration: `snapshot_plugin_names` (`snapshot_id` FK cascade,
      `plugin_name TEXT NOT NULL`, `location TEXT`), index on `snapshot_id`.
      No `normalized_key` column — design decision 4
- [ ] 2.3 Comment both tables in the style of `V1__init.sql`: why `state` is
      absent from `facts`, and why no normalised key is stored

## 3. Facts as persisted state (SVC_GW_0194)

- [ ] 3.1 `SnapshotFactsService`: `build` stops putting `state` into the
      `snapshot` map; a new `store(Snapshot, Marketplace)` persists the map and
      the plugin-name rows; a new `load(Snapshot)` returns the stored map with
      the current `state` re-injected. Annotate `@Requirements({"GW_0194"})`
- [ ] 3.2 `SnapshotFactsRepository` (new, `dev.skillsgateway.server.policy`):
      `store`, `load`, `approvedEstatePluginNames()`, `unindexedApprovedCount()`
- [ ] 3.3 `IngestionService`: call `store` after closure resolution and before
      `vettingService.vet`; a `PolicyEvaluationException` is caught and written
      as `unavailable_reason`, never rethrown into ingestion
- [ ] 3.4 `PolicyGate`: read via `load`; a row carrying `unavailable_reason`
      produces the same per-rule `error: …` denials it produces today for a
      build failure. Behaviour unchanged — assert it against the existing
      policy SVC tests without weakening them
- [ ] 3.5 Backfill `ApplicationRunner`: idempotent, bounded batches, logged, for
      every approved non-deleted snapshot with no facts row (design decision 5)
- [ ] 3.6 Fix the stale `CelPolicy` javadoc while here — it omits
      `snapshot.upstreamSha`, `snapshot.externalSources` and the plugin
      `origin`/`upstreamUrl`/`resolvedSha` fields the service already emits

## 4. Normalisation (SVC_GW_0195.1)

- [ ] 4.1 Vendor the UTS #39 confusables table as a dated resource under
      `src/main/resources/vetting/`, following the `agentskills-*.json`
      precedent — no runtime download
- [ ] 4.2 `NameNormalizer` (new): NFKC → casefold → confusable skeleton →
      collapse `-`, `_`, `.`, whitespace. Pure, no I/O, table loaded once

## 5. The gate (SVC_GW_0195.1–.3, SVC_GW_0196.1–.2, SVC_GW_0197)

- [ ] 5.1 `SkillsGatewayProperties`: a `Collision(Mode mode)` component,
      `skills-gateway.approval.collision.mode` ∈ `off | warn | enforce`,
      **default `warn`** (design decision 8). Shared record — see Risks
- [ ] 5.2 `CollisionGate` (new, `dev.skillsgateway.server.approval`): loads the
      approved estate's plugin names, excludes the snapshot's own marketplace,
      normalises both sides, returns the matches. Annotate
      `@Requirements({"GW_0195"})`
- [ ] 5.3 `CollisionGate` consults `WaiverService` with rule id
      `plugin-name-collision`, location `.claude-plugin/marketplace.json`, and
      suppresses a covered match. Annotate `@Requirements({"GW_0196"})`
- [ ] 5.4 A distinct refusal when the estate is not fully indexed, never
      reported as a collision (design decision 5)
- [ ] 5.5 `ApprovalService.doApprove`: call the gate with the release-age gate,
      before any state transition; `warn` mode returns the matches on `Approved`
      for the ledger, `enforce` mode throws
- [ ] 5.6 Post-transition re-check between `decide` and `publish`, reusing the
      existing `repair`/`undecide` path (design decision 6). Annotate
      `@Requirements({"GW_0195"})`
- [ ] 5.7 The ADR 0010 override (`ApprovalOverride.vettingFailure`) does **not**
      lift this gate — assert it, do not merely avoid wiring it
- [ ] 5.8 `AdminController`: a `409` problem document type for the collision
      refusal carrying the offending name, the normalised key, and the incumbent
      marketplace and snapshot id. Additive; no existing field changes
- [ ] 5.9 Ledger events `approval-collision-refused` and
      `approval-collision-warned` via `AdminAuditLogger`. Annotate
      `@Requirements({"GW_0197"})`

## 6. Frontend

- [ ] 6.1 Snapshot review surface: render a collision refusal with the incumbent
      named, and offer the existing waiver flow at `SNAPSHOT` scope only
      (design decision 7). JSDoc `@requirements GW_0197`
- [ ] 6.2 Regenerate `src/main/frontend/openapi.json` and `types.gen.ts`

## 7. Tests — trust boundary, so adversarial not happy-path

Follow `.claude/skills/old-coder`: prove each test fails before the
implementation lands. Never weaken an existing SVC test.

- [ ] 7.1 `NameNormalizerTests` (plain JUnit, `SVC_GW_0195.1`): the case,
      separator and confusable table, including pairs that must **not** match;
      no edit-distance behaviour
- [ ] 7.2 `CollisionGateTests` (`AbstractGatewayTest`, `SVC_GW_0195.2`): a
      colliding snapshot is refused in `enforce`; the **incumbent stays approved
      and served**; the same marketplace's own history never collides; `warn`
      mode approves and records
- [ ] 7.3 `CollisionRaceTests` (`SVC_GW_0195.3`): two colliding approvals issued
      concurrently — at most one is approved, nothing is published for the
      other, and no snapshot is left decided-but-unpublished. **This is the test
      that decides whether design decision 6 survives**; if it does not, stop
      and propose the advisory lock as its own change
- [ ] 7.4 `CollisionWaiverTests` (`SVC_GW_0196.1`): a waiver on
      `plugin-name-collision` at `SNAPSHOT` scope permits the approval; an
      expired or revoked one does not
- [ ] 7.5 `SVC_GW_0196.2`: the ADR 0010 vetting override does **not** lift a
      collision refusal
- [ ] 7.6 `SnapshotFactsPersistenceTests` (`SVC_GW_0194`): facts stored at
      ingestion match what `build` returns; `state` is not stored and is current
      on read across a `held → approved → revoked` sequence; a snapshot over
      `MAX_FILES` stores `unavailable_reason` and its approval is refused
- [ ] 7.7 `SVC_GW_0198`: a chain run over unchanged content produces an
      identical `vetting_runs.chain` and identical verdicts before and after an
      unrelated approval changes the estate — the purity `GW_0049` depends on
- [ ] 7.8 `SVC_GW_0197`: the ledger carries the refusal and the warning with the
      incumbent named
- [ ] 7.9 Annotate every test above with `@SVCs({...})`

## 8. Docs (same PR)

- [ ] 8.1 `concepts/vetting.md`: the collision precondition, next to the
      minimum-release-age section that states the same principle, and why it is
      deliberately not a connector
- [ ] 8.2 `guides/approving-snapshots.md`: the refusal and what to do about it
- [ ] 8.3 `guides/waiving-findings.md`: `plugin-name-collision` as a waivable
      rule id, and the `SNAPSHOT`-scope widening stated plainly
- [ ] 8.4 `reference/configuration.md`: `skills-gateway.approval.collision.mode`
- [ ] 8.5 `reference/api/snapshots.md`: the new `409` problem document type
- [ ] 8.6 `architecture.md`: the T5 row moves off "No" — partially, and only for
      the plugin-name half
- [ ] 8.7 `reference/decisions.md`: flip ADR 0014 to *Accepted*

## 9. Gates and archive

- [ ] 9.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [ ] 9.2 `openspec/changes/corpus-aware-vetting/evidence.md` refreshed for the
      implementation run
- [ ] 9.3 Archive the change as the final commit of the implementing PR
