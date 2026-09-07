# Tasks: sweep-abandoned-staging-refs

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0168 (an abandoned publication staging reference is removed and
      its objects reclaimed, only when no live snapshot record names its commit
      and it has been observed for longer than a configured bound; served
      references and approved content untouched; the removal recorded on the
      ledger) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0168 (GIVEN/WHEN/THEN, both backends) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Backend (SVC_GW_0168)

- [x] 2.1 `V1__init.sql`: `staging_ref_sightings (marketplace, ref,
      first_seen_at)`, unique on `(marketplace, ref)`
- [x] 2.2 `StagingRefSightingRepository`: `observe` (stamp the new, forget the
      gone, read back the stamps, one transaction) and `forget`
- [x] 2.3 `SkillsGatewayProperties.Retention`: `stagingRefMaxAge` with a `24h`
      default and a `stagingSweepEnabled()` fail-safe on zero or negative
- [x] 2.4 `RetentionService.sweepStagingRefs`: list `refs/staging/*` per
      published repository, stamp, select the ones past the bound that no live
      snapshot row names, delete them, garbage-collect, record on the ledger.
      Annotated `@Requirements({"GW_0168"})`
- [x] 2.5 `RetentionService.compact` calls it, with its failures caught
      separately; `collectGarbage` becomes role-aware

## 3. Tests (never weakening an existing SVC test)

- [x] 3.1 `StagingRefSweepTests`, `@SVCs({"SVC_GW_0168"})`, parameterized over
      the filesystem and object-store backends: an abandoned reference past the
      bound is swept and its commit becomes reachable from nothing; a reference
      inside the bound survives *and its publication still completes and
      serves*; a reference a live snapshot row names survives however old; the
      served references and their content are untouched
- [x] 3.2 The filesystem case that the objects are physically reclaimed, not
      only unreferenced
- [x] 3.3 The wiring: an on-demand compaction pass runs the sweep, records it on
      the ledger, and leaves approved content clonable through the facade
- [x] 3.4 `staging-ref-max-age: 0` switches the sweep off rather than sweeping
      everything

## 4. Docs (same PR)

- [x] 4.1 `docs/manual/reference/retention.md`: the sweep, its two conditions,
      the bound and what it protects — and the correction that retention now
      does touch the published repository
- [x] 4.2 `docs/manual/reference/configuration.md`: `staging-ref-max-age` in the
      retention YAML sample and the property table
- [x] 4.3 `docs/manual/guides/snapshot-retention.md`: what an operator sees

## 5. Gates and archive

- [x] 5.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [x] 5.2 `openspec/changes/sweep-abandoned-staging-refs/evidence.md`
- [x] 5.3 Archive the change as the final commit of the PR
