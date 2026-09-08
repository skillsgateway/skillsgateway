# Tasks: invocation-adoption-metrics

This change is **design only**. Nothing below is implemented by the PR that
introduces it; the PR carries
[ADR 0016](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0016-client-invocation-telemetry-is-not-ingested.md)
and this proposal, and the ADR is `proposed`. **Do not start section 2 before the
ADR is accepted** — sections 2 onward exist because the decision they implement
is the ADR's, and implementing them first would make the ADR a description of
something already built.

## 1. Requirements (SSOT first, in the implementing PR)

Deliberately deferred out of the design PR: a requirement on `main` with no
`@Requirements` annotation and no passing verification case turns the reqstool
gate red, so the entries land with the code that satisfies them.

- [ ] 1.1 Add GW_0213 (skill-level presence inventory from the fetch ledger and
      the pinned commit tree), GW_0214 (the presence report is labelled presence
      and carries the identifiers an external backend joins on) and GW_0215 (no
      client-reported telemetry is ingested or reported) to
      `docs/reqstool/requirements.yml`
- [ ] 1.2 Add SVC_GW_0213, SVC_GW_0214 and SVC_GW_0215 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`. SVC_GW_0215 is structural —
      see 4.1 — and must be written so it fails on a future change, not only on
      this one

## 2. Presence resolution

- [ ] 2.1 A `SnapshotContentResolver` seam over `SnapshotContentService.content`,
      addressed by `(marketplace, sha)` rather than snapshot id, returning an
      explicit unresolvable outcome for a SHA whose objects retention reclaimed or
      whose manifest no longer parses (GW_0213, design decision 4)
- [ ] 2.2 A bounded per-`(marketplace, sha)` cache with a size cap and a hit
      metric under the `skills_gateway` prefix; no eviction on age, because a
      pinned commit's content is immutable (design decision 3)
- [ ] 2.3 `@Requirements` annotations on both

## 3. The report

- [ ] 3.1 `AdoptionService.presence(...)`: the window's distinct SHAs from
      `FetchLogRepository`, re-keyed onto `(marketplace, plugin, skill)` with the
      existing per-SHA measures and the distinct-snapshot span (GW_0213)
- [ ] 3.2 The SHA set comes only from ledger rows, never from a caller-supplied
      snapshot id; names and paths only, never file bytes (GW_0213, design
      decision 2)
- [ ] 3.3 Response field names and an explicit uniformity statement in the payload
      (GW_0214, design decision 5)
- [ ] 3.4 `AdoptionController` route under `/api/adoption`, auditor-or-admin gated
      like the ledger reads; resolve the machine-scope open question and, if it is
      a new scope, extend `skills-gateway.estate.*` in the same PR per `CLAUDE.md`
- [ ] 3.5 `@Requirements` annotations

## 4. The boundary

- [ ] 4.1 A structural test asserting no mapped `POST`/`PUT`/`PATCH` handler
      outside the known operator, publisher and machine surfaces, and asserting
      `/v1/metrics`, `/v1/logs` and `/v1/traces` absent by name (GW_0215, design
      decision 6)
- [ ] 4.2 A test asserting no adoption response field is sourced from anything
      other than the ledger and published storage (GW_0215)

## 5. Adversarial and negative tests

- [ ] 5.1 An auditor cannot name the contents of a **held** snapshot through the
      presence report — the case design decision 2 exists to close
- [ ] 5.2 A SHA whose objects were reclaimed appears with its ledger measures and
      the unresolvable marker, never omitted (GW_0213)
- [ ] 5.3 A snapshot whose manifest does not parse is unresolvable, not empty
      (GW_0213)
- [ ] 5.4 Role enforcement: a non-auditor, non-admin session is refused
- [ ] 5.5 A marketplace serving nothing, and an empty window, both answer without
      error

## 6. Portal

- [ ] 6.1 A presence section on the existing adoption page (GW_OBSERVABILITY_0004 — *Adoption
      page in the admin portal* keeps its scope; no new page), with loading, empty
      and error states, and the uniformity line above the table
- [ ] 6.2 Storybook stories covering those states; JSDoc requirement tags

## 7. Documentation (same PR as the implementation)

- [ ] 7.1 `reference/api/adoption.md` — the new endpoint, and a `!!! warning`
      beside the existing "Identities, not teams" note stating that per-skill
      counts are uniform within a snapshot
- [ ] 7.2 `reference/observability.md` — a section stating that the gateway
      ingests no client-reported telemetry and where client telemetry goes instead
- [ ] 7.3 `concepts/trust-boundaries.md` — client telemetry named under "What is
      not a boundary yet" as a boundary the gateway deliberately does not have
- [ ] 7.4 `reference/portal.md` — the new section
- [ ] 7.5 `architecture.md` §9 — rewrite the install-inventory bullet, which today
      offers enrichment "optionally … with client OTel telemetry"; §13's Phase 3
      line, which lists "client telemetry inventory"; and add the upstream ask
      (an identifier for enterprise-configured marketplaces that survives
      third-party redaction) beside the client-enforcement ask in §14
- [ ] 7.6 Flip ADR 0016 to `accepted` and update its `docs/manual/reference/decisions.md`
      entry, if it has not already been accepted separately

## 8. Gates and archive

- [ ] 8.1 Full gate run, `evidence.md` written from one final fresh run
- [ ] 8.2 Archive this change
