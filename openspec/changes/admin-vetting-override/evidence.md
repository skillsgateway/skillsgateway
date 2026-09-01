# Evidence: admin-vetting-override

Tier 3 (trust boundary: `ApprovalService` and the vetting chain). This report
records what was executed locally and what is deferred, honestly.

## Spec ↔ test mapping

| Requirement | Verifies | Test(s) |
| --- | --- | --- |
| GW_0142 — admin override of a blocked outcome | admin-only, reason-required, distinct ledger event, fail-loud marker, lifts only the vetting gate | `VettingOverrideTests.an_admin_overrides_a_blocked_outcome_with_a_reason_and_no_one_else_can` (SVC_GW_0142) |
| GW_0143 — connector enable/disable | disabled verdict non-blocking, positive evidence still required, admin-only, audited | `VettingChainDisabledTests` (5 cases, SVC_GW_0143); `ConnectorToggleTests` (2 cases, SVC_GW_0143) |

Negative/adversarial cases (the guardrails):

- Non-admin cannot override (`403`) and cannot toggle a connector or read the
  settings (`403`) — `VettingOverrideTests`, `ConnectorToggleTests`.
- An override with no reason is refused (`422`) — `VettingOverrideTests`.
- The trail is always written: a distinct `snapshot-approved-over-vetting-failure`
  ledger event on override, `connector-disabled` on a toggle —
  `VettingOverrideTests`, `ConnectorToggleTests`.
- Disabling every connector leaves a run `BLOCKED`, never cleared —
  `VettingChainDisabledTests.disabling_every_connector_blocks_rather_than_clears`
  (pure function) and `ConnectorToggleTests.disabling_every_connector_leaves_a_run_blocked_not_clear`
  (through the real chain).
- `RoleEnforcementTests` classifies the two new routes; its route-table walk
  fails if a new mutation is left unclassified.

## Gates run locally

| Gate | Command | Result |
| --- | --- | --- |
| Java compile | `./mvnw -DskipTests compile` | BUILD SUCCESS |
| Test compile | `./mvnw -DskipTests test-compile` | BUILD SUCCESS |
| Formatting | `./mvnw spotless:check` | BUILD SUCCESS |
| Style | `./mvnw checkstyle:check` | BUILD SUCCESS |
| Pure-function tests | `./mvnw -Dtest=VettingChainDisabledTests test` | Tests run: 5, Failures: 0, Errors: 0 |
| OpenSpec | `openspec validate --all --strict` | 27 passed, 0 failed |
| Docs | `mkdocs build --strict` | Documentation built, no strict failures |

## Gates deferred (blocked by the local container environment)

The Spring integration suites and everything downstream of them could **not** run
locally: the Arconia `floci` dev-service (`floci/floci:1.6.0`, the AWS emulator
S3 backend) fails to start its container in this shared Podman VM —
`Container startup failed for image floci/floci:1.6.0`, "no stdout/stderr logs
available for the failed container". This is **not** introduced by this change:
the untouched, pre-existing `ApprovalTests` fails identically. Retried once with
`TESTCONTAINERS_RYUK_DISABLED=true` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`;
same failure.

Deferred to CI (where the dev services start), and to be run serially pre-merge:

- `VettingOverrideTests`, `ConnectorToggleTests` (written, compile clean, not
  executed locally).
- `RoleEnforcementTests` route-table walk (updated, not executed).
- `./mvnw clean verify` (full suite), portal `pnpm test:stories`, `pnpm e2e`.
- `reqstool status local -p docs/reqstool` — needs the surefire results the
  blocked suites produce.
- **`openapi.json` regeneration** — the approve endpoint gained an optional body,
  the vetting view an `override` field, and there are two new endpoints. The
  committed `src/main/frontend/openapi.json` and `src/api/types.gen.ts` must be
  regenerated from `OpenApiDocsTests` (a Spring test, blocked locally by Floci) so
  `OpenApiContractTests` passes. This is the one gate expected red in CI until the
  regeneration is committed; it is an additive change (no path-prefix move), so it
  is compatible within the major.

## Source state

HEAD of `feat/admin-vetting-override`.
