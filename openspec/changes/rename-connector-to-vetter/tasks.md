# Tasks: rename-connector-to-vetter

## 1. Java

- [x] 1.1 `VettingConnector` → `Vetter`; `SecretScanConnector`,
      `PromptInjectionConnector`, `LicenseScanConnector`,
      `SkillConformanceConnector` → `*Vetter`
- [x] 1.2 `ConnectorToggle`, `ConnectorToggleRepository`,
      `ConnectorToggleService`, `ConnectorToggleController` → `VetterToggle*`;
      `ChainConnectorView` → `ChainVetterView`; `VettingController`'s
      `ConnectorView` → `VetterView`
- [x] 1.3 Every field, parameter, method, javadoc, log message, `@Operation`
      text, `@Schema` description and error message with them
- [x] 1.4 `ExternalVettingConnector`, `ExternalConnectorProperties`,
      `ExternalVettingConnectorRegistrar` and the
      `skills-gateway.vetting.external` prefix keep their names;
      `ExternalVettingConnector implements Vetter`, and its javadoc says it is
      the connector that turns an external endpoint into a vetter
- [x] 1.5 Ledger events `vetter-disabled` / `vetter-enabled`; the
      `vetting-verdict` detail leads `vetter=state`; `revet-violation` carries
      `vetters=`
- [x] 1.6 Finding rule ids `vetter-error` and `vetter-not-run` (not
      `vetter-disabled` — see design, Decision 3)
- [x] 1.7 Test sources follow, including `ConnectorToggleTests` →
      `VetterToggleTests` and `SkillConformanceConnectorTests` →
      `SkillConformanceVetterTests`; `MachineApiRegistry` and
      `RoleEnforcementTests` route tables

## 2. Database

- [x] 2.1 `V2__rename_connector_to_vetter.sql`: `vetting_verdicts.connector`,
      `snapshot_vetting_overrides.blocking_connectors`, the
      `connector_toggles` table with its column, constraints, index and
      sequence
- [x] 2.2 The same migration rewrites stored waiver rule ids
      `connector-error` → `vetter-error` and `connector-disabled` →
      `vetter-not-run`; `V1__init.sql` is not edited

## 3. The contract

- [x] 3.1 `PUT /api/vetting/vetters/{name}/toggle` and
      `GET /api/vetting/vetter-toggles`;
      `GET /api/marketplaces/{name}/vetting-chain` keeps its path with a
      renamed item type
- [x] 3.2 Regenerate `src/main/frontend/openapi.json` from the build's
      `target/openapi.json` and `src/api/types.gen.ts` with `pnpm gen:api-types`

## 4. Portal

- [x] 4.1 `vetting-flow.ts`, `vetting-flow.tsx`, `vetting-report.tsx`,
      `marketplace-vetting-chain.tsx`, `queries.ts`, stories, tests, MSW
      handlers, e2e
- [x] 4.2 UI copy: "What these vetters can and cannot see", node labels,
      headline sentences, the marketplace chain card

## 5. Requirements (SSOT)

- [x] 5.1 `docs/reqstool/requirements.yml` and
      `software_verification_cases.yml`: every title, description and rationale
      meaning the chain element says vetter; those meaning the external
      transport keep connector. Ids unchanged, revisions bumped
- [x] 5.2 Delta specs for the seven capabilities whose requirements moved

## 6. Documentation

- [x] 6.1 All of `docs/manual`; `vetting.md` retitled *Vetting — the vetter
      chain*, its *External connectors* section kept and reworded to "each
      contributes one vetter"
- [x] 6.2 Glossary: a **Vetter** entry; **Connector** narrowed to the transport
      with the rule-of-thumb sentence
- [x] 6.3 `reference/decisions.md`: dated renamed-on notes under ADR 0009,
      ADR 0010 and ADR 0015; `docs/decisions/*.md` untouched
- [x] 6.4 `docs/diagrams/lifecycle.mmd` and its regenerated SVG pair, manifest
      hash refreshed
- [x] 6.5 `PRODUCT.md`, `DESIGN.md`, `.claude/skills/architecture/SKILL.md`,
      `docs/manual/capability-map.md`

## 7. Gates

- [x] 7.1 Run the gates and record `evidence.md`
- [x] 7.2 `grep -rniw connector … | grep -vi external` is near-empty, and every
      remaining hit is justified in the PR notes
- [x] 7.3 Archive this change as the PR's final commit
