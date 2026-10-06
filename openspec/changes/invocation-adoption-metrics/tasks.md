# Tasks: invocation-adoption-metrics

ADR 0016 was accepted on 2026-09-23 and this change is implemented by the PR
for issue #85. The ids reserved as GW_0213–GW_0215 landed as
GW_OBSERVABILITY_0006–0008 under the namespaced scheme `main` adopted meanwhile.

## 1. Requirements (SSOT first, in the implementing PR)

Deliberately deferred out of the design PR: a requirement on `main` with no
`@Requirements` annotation and no passing verification case turns the reqstool
gate red, so the entries land with the code that satisfies them.

- [x] 1.1 Add GW_OBSERVABILITY_0006 (skill-level presence inventory from the fetch ledger and
      the pinned commit tree), GW_OBSERVABILITY_0007 (the presence report is labelled presence
      and carries the identifiers an external backend joins on) and GW_OBSERVABILITY_0008 (no
      client-reported telemetry is ingested or reported) to
      `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_OBSERVABILITY_0006, SVC_GW_OBSERVABILITY_0007 and SVC_GW_OBSERVABILITY_0008 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`. SVC_GW_OBSERVABILITY_0008 is structural —
      see 4.1 — and must be written so it fails on a future change, not only on
      this one

## 2. Presence resolution

- [x] 2.1 A `SnapshotContentResolver` seam over `SnapshotContentService.content`,
      addressed by `(marketplace, sha)` rather than snapshot id, returning an
      explicit unresolvable outcome for a SHA whose objects retention reclaimed or
      whose manifest no longer parses (GW_OBSERVABILITY_0006, design decision 4)
- [x] 2.2 A bounded per-`(marketplace, sha)` cache with a size cap and a hit
      metric under the `skills_gateway` prefix; no eviction on age, because a
      pinned commit's content is immutable (design decision 3)
- [x] 2.3 `@Requirements` annotations on both

## 3. The report

- [x] 3.1 `AdoptionService.presence(...)`: the window's distinct SHAs from
      `FetchLogRepository`, re-keyed onto `(marketplace, plugin, skill)` with the
      existing per-SHA measures and the distinct-snapshot span (GW_OBSERVABILITY_0006)
- [x] 3.2 The SHA set comes only from ledger rows, never from a caller-supplied
      snapshot id; names and paths only, never file bytes (GW_OBSERVABILITY_0006, design
      decision 2)
- [x] 3.3 Response field names and an explicit uniformity statement in the payload
      (GW_OBSERVABILITY_0007, design decision 5)
- [x] 3.4 `AdoptionController` route under `/api/adoption`, auditor-or-admin gated
      like the ledger reads; resolve the machine-scope open question and, if it is
      a new scope, extend `skills-gateway.estate.*` in the same PR per `AGENTS.md`
- [x] 3.5 `@Requirements` annotations

## 4. The boundary

- [x] 4.1 A structural test asserting no mapped `POST`/`PUT`/`PATCH` handler
      outside the known operator, publisher and machine surfaces, and asserting
      `/v1/metrics`, `/v1/logs` and `/v1/traces` absent by name (GW_OBSERVABILITY_0008, design
      decision 6)
- [x] 4.2 A test asserting no adoption response field is sourced from anything
      other than the ledger and published storage (GW_OBSERVABILITY_0008)

## 5. Adversarial and negative tests

- [x] 5.1 An auditor cannot name the contents of a **held** snapshot through the
      presence report — the case design decision 2 exists to close
- [x] 5.2 A SHA whose objects were reclaimed appears with its ledger measures and
      the unresolvable marker, never omitted (GW_OBSERVABILITY_0006)
- [x] 5.3 A snapshot whose manifest does not parse is unresolvable, not empty
      (GW_OBSERVABILITY_0006)
- [x] 5.4 Role enforcement: a non-auditor, non-admin session is refused
- [x] 5.5 A marketplace serving nothing, and an empty window, both answer without
      error

## 6. Portal

- [x] 6.1 A presence section on the existing adoption page (GW_OBSERVABILITY_0004 — *Adoption
      page in the admin portal* keeps its scope; no new page), with loading, empty
      and error states, and the uniformity line above the table
- [x] 6.2 Storybook stories covering those states; JSDoc requirement tags

## 7. Documentation (same PR as the implementation)

- [x] 7.1 `reference/api/adoption.md` — the new endpoint, and a `!!! warning`
      beside the existing "Identities, not teams" note stating that per-skill
      counts are uniform within a snapshot
- [x] 7.2 `reference/observability.md` — a section stating that the gateway
      ingests no client-reported telemetry and where client telemetry goes instead
- [x] 7.3 `concepts/trust-boundaries.md` — client telemetry named under "What is
      not a boundary yet" as a boundary the gateway deliberately does not have
- [x] 7.4 `reference/portal.md` — the new section
- [x] 7.5 `architecture.md` §9 — rewrite the install-inventory bullet, which today
      offers enrichment "optionally … with client OTel telemetry"; §13's Phase 3
      line, which lists "client telemetry inventory"; and add the upstream ask
      (an identifier for enterprise-configured marketplaces that survives
      third-party redaction) beside the client-enforcement ask in §14
- [x] 7.6 Flip ADR 0016 to `accepted` and update its `docs/manual/reference/decisions.md`
      entry, if it has not already been accepted separately

## 8. Gates and archive

- [x] 8.1 Full gate run, `evidence.md` written from one final fresh run
- [x] 8.2 Archive this change

## 9. Review follow-ups

- [x] 9.1 The ledger trim keeps each identity's latest pack send per marketplace
      (GW_RETENTION_0012), with `SVC_GW_RETENTION_0012` in `LedgerTrimTests`, and
      the retention guide and adoption reference say so
- [x] 9.2 The boundary test's OTLP probe asserts 404 rather than any non-2xx, and
      carries a CSRF token so a CSRF refusal cannot pass for "not mapped"
- [x] 9.3 One presence pass lists the stored marketplaces at most once, and the
      content cache holds more delivered SHAs than an estate approves in years
- [x] 9.4 A SHA counts once per skill even when a manifest repeats a plugin name
- [x] 9.5 The cache-hit assertion measures the second read alone
- [x] 9.6 The skill's `SKILL.md` path is visible in the portal, not only on hover
- [x] 9.7 `/impeccable audit` and `harden` on the Adoption page; material findings fixed
- [ ] 9.8 Gates re-run, `evidence.md` rewritten with the commit SHA, change re-archived

## Where the implementation departs from the text above

- **Holding is latest-fetch.** 3.1 says "the window's distinct SHAs"; the
  report is window-free (see `design.md`, Open Questions) and an identity holds
  what its most recent fetch of a marketplace received, from the same
  `DISTINCT ON` read staleness uses. Each identity then counts against one SHA
  per marketplace, so per-SHA counts sum. The set of delivered SHAs is still
  every `upload-pack` row, all time.
- **Resolution reads quarantine, then published.** Quarantine survives
  revocation, which is what a recall needs; a virtual-catalog commit exists only
  in the published repository. Neither repository is created by the lookup.
- **Unresolved answers are not cached** (2.2), so a storage outage does not pin
  "unknown" onto a commit that is still there.
- **4.1 is a prefix rule**, not a list: every write route must sit under
  `/api/v1/`, `/hooks/`, `/status/v1/`, `/session` or Spring's `/error`, and
  none may be named for telemetry. `RoleEnforcementTests` already pins the
  exact `/api/v1/` route set.
