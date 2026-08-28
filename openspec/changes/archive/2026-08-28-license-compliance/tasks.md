# Tasks — license compliance for skills

## 1. Requirements SSOT first

- [x] 1.1 Add GW_0093 (deterministic license detection connector), GW_0094
      (allow/ban policy through the standard vetting path), GW_0095
      (per-snapshot license report endpoint) to
      `docs/reqstool/requirements.yml`.
- [x] 1.2 Add SVC_GW_0093–SVC_GW_0095 to
      `docs/reqstool/software_verification_cases.yml`.

## 2. Tests first (record that they fail)

- [x] 2.1 `LicenseTests` (extends `AbstractGatewayTest`):
      detection of common licenses from LICENSE/COPYING files and SPDX tags,
      manifest `license` metadata, unknown-license and missing-license states
      (SVC_GW_0093); ban list blocks, allow list blocks absentees and
      unknown, defaults warn only, banned finding waivable through the
      standard gate, policy digest visible in the chain identity
      (SVC_GW_0094); licenses endpoint reports detections and policy
      evaluation, 404 for a missing snapshot (SVC_GW_0095).
- [x] 2.2 Run the new tests and record that they fail before implementation.

## 3. Implementation

- [x] 3.1 `SkillsGatewayProperties.Vetting.License` — `allowed`, `banned`
      lists (SPDX ids), defaults empty.
- [x] 3.2 `LicenseDetector` — fingerprint table, SPDX tag parsing, manifest
      metadata extraction; pure and deterministic, with a table version.
- [x] 3.3 `LicenseScanConnector` — order 300, findings per D3, `version()`
      carries table version + policy digest.
- [x] 3.4 `LicenseReportService` + `GET /api/snapshots/{id}/licenses` on
      `AdminController` with OpenAPI annotations.
- [x] 3.5 Adapt `VettingTests` chain-position assertion to derive from the
      configured chain (no weakening); regenerate `openapi.json` and
      `types.gen.ts`.

## 4. Docs (same PR)

- [x] 4.1 `docs/manual/reference/configuration.md` — the
      `skills-gateway.vetting.license.*` block.
- [x] 4.2 `docs/manual/reference/api/marketplaces.md` — the licenses
      endpoint.
- [x] 4.3 `docs/manual/concepts/vetting.md` — the license-scan connector and
      its rule ids.
- [x] 4.4 New guide `docs/manual/guides/license-compliance.md` + mkdocs nav.

## 5. Gates and evidence

- [x] 5.1 `./mvnw clean verify`
- [x] 5.2 `(cd src/main/frontend && pnpm test:stories)` and
      `(cd src/main/frontend && pnpm e2e)` — the story-test gate was added to
      `CLAUDE.md` after this task list was written; both were run.
- [x] 5.3 `reqstool status local -p docs/reqstool` ends PASS
- [x] 5.4 `openspec validate --all --strict`
- [x] 5.5 `mkdocs build --strict`
- [x] 5.6 Write `openspec/changes/license-compliance/evidence.md`
- [x] 5.7 Archive the change as the final commit of the PR
