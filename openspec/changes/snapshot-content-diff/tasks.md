# Tasks: snapshot-content-diff

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0150 (inventory diff of a snapshot against the marketplace's
      newest approved snapshot, at plugin and skill granularity, with
      added/removed/changed/moved/unchanged, relocation reported once, and an
      explicit no-baseline answer) to `docs/reqstool/requirements.yml`
- [ ] 1.2 Add SVC_GW_0150 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Backend (SVC_GW_0150)

- [ ] 2.1 `SnapshotRepository.latestApprovedByMarketplace(marketplaceId,
      excludingSnapshotId)` — newest live approved snapshot, or empty
- [ ] 2.2 `SnapshotContentService`: `ContentDiff` / `PluginDiff` / `SkillDiff` /
      `DiffSummary` records with `@Schema`, and `diff(snapshotId)` annotated
      `@Requirements({"GW_0150"})` — tree-object comparison per skill directory,
      move detection by skill name across plugins, removed plugins retained from
      the baseline, null baseline reported as such
- [ ] 2.3 `AdminController`: `GET /api/snapshots/{id}/content-diff` with `@Tag`,
      `@Operation`, `@ApiResponse`

## 3. Tests (never weakening an existing SVC test)

- [ ] 3.1 `ContentDiffTests` extends `AbstractGatewayTest`,
      `@SVCs({"SVC_GW_0150"})`: first snapshot has no baseline and reports
      everything added; a second snapshot over an approved one reports an added
      skill, a changed skill (edited helper file, not `SKILL.md`), a removed
      plugin with its skills, and a skill moved between plugins reported once as
      moved; unknown snapshot answers 404

## 4. Portal

- [ ] 4.1 `api/queries.ts`: `SnapshotContentDiff` types + `useSnapshotContentDiff`
- [ ] 4.2 `components/snapshot-content-diff.tsx`: loading / error / no-baseline /
      no-changes / changes states, JSDoc `@Requirements GW_0150`
- [ ] 4.3 Render it inside the **Show contents** panel of
      `pages/marketplace-detail.tsx`
- [ ] 4.4 MSW handler for the new endpoint; component test beside
      `marketplace-detail.test.tsx`
- [ ] 4.5 Regenerate `openapi.json` and `src/api/types.gen.ts`

## 5. Docs (same PR)

- [ ] 5.1 `docs/manual/reference/api/marketplaces.md` — the new endpoint
- [ ] 5.2 `docs/manual/reference/portal.md` — the changes section of the panel
- [ ] 5.3 `docs/manual/guides/approving-snapshots.md` — where it fits the review

## 6. Gates

- [ ] 6.1 `openspec validate --all --strict`
- [ ] 6.2 `./mvnw clean verify`
- [ ] 6.3 `reqstool status local -p docs/reqstool` ends PASS
- [ ] 6.4 `mkdocs build --strict`
- [ ] 6.5 `evidence.md` written from one final fresh run
