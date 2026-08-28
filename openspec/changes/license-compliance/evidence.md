# Evidence — license-compliance

One fresh run of every gate after the last code edit.

**Commit:** `a6b0567755eaf0952c8425376950498ca677aaf6`
(`feat(vetting): license detection, allow/ban policy, and license report (#105)`)

This change's implementation shipped in PR #105 (issue #30) but was never closed
out: tasks 5.1–5.7 were left unchecked, no evidence report was written, and the
change was never archived. This report settles that debt. It carries no code
edits of its own — the gates below were run against `origin/main` at `b20af9e`,
whose `src/`, `docs/reqstool/` and `docs/manual/` content for this capability is
exactly what `a6b0567` landed. Tasks 1–4 were re-verified against the tree
before running the gates:

- `GW_0093`–`GW_0095` in `docs/reqstool/requirements.yml` and
  `SVC_GW_0093`–`SVC_GW_0095` in `docs/reqstool/software_verification_cases.yml`
- `LicenseDetector`, `LicensePolicy`, `LicenseEvaluation`, `LicenseScanConnector`,
  `LicenseReportService` under `src/main/java/dev/skillsgateway/server/vetting/`,
  carrying `@Requirements` for GW_0093–GW_0095
- `SkillsGatewayProperties.Vetting.License` (`allowed`, `banned`, both defaulting
  to empty)
- `GET /api/snapshots/{id}/licenses` on `AdminController`, present in
  `src/main/frontend/openapi.json` and `types.gen.ts`
- `LicenseTests` and `LicensePolicyTests` carrying `@SVCs` for
  SVC_GW_0093–SVC_GW_0095
- `VettingTests` derives its chain-position assertion from
  `vettingService.connectors()` rather than hard-coding two connectors
- docs: `docs/manual/reference/configuration.md`,
  `docs/manual/reference/api/marketplaces.md`, `docs/manual/concepts/vetting.md`,
  and `docs/manual/guides/license-compliance.md` in the mkdocs nav

## `./mvnw clean verify`

```
[INFO] Tests run: 182, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:32 min
```

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

This gate did not exist when the task list was written; it is run here because
`CLAUDE.md` now requires it.

## `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (32.9s)
```

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
106/106 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
Totals: 26 passed, 0 failed (26 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 0.69 seconds
```
