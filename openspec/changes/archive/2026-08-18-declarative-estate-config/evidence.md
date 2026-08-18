# Evidence: declarative-estate-config

One final fresh run of every gate after the last code edit
(`1379e49700c0394c0d8d168d5db55565fdf558b6`; edits after it are this report,
the tasks checklist, and the archive move — no source changes). Final commit
SHA at the bottom.

## Discipline notes (old-coder Tier 3 — registration allowlist and role grants are trust boundaries)

- Spec approval: not obtained per-spec (autonomous run under the owner's
  delegated implementation brief for issue #65, which fixed the design rails:
  additive-only, start-and-report on invalid entries, secrets by reference,
  grants may reference API-registered marketplaces). The committed OpenSpec
  change (proposal/design/specs/tasks, commit d4f88ae) is the spec the
  implementation was held to; every rail from the brief is resolved and
  recorded in `design.md`.
- **RED observed before GREEN**: with `EstateReconciler.reconcile` stubbed to
  a no-op, all 7 `EstateReconciliationTests` behaviors and
  `EstateStartupFailureTests` failed on assertions/behavior
  (`Tests run: 8, Failures: 4, Errors: 3` plus the startup-failure test), not
  on compilation. The extraction refactor that preceded it was validated
  separately under green: the full pre-existing suite (78 tests) passed
  unchanged after controllers were made thin delegates, before any new
  behavior existed.
- **Trust-boundary mutants** (each killed, then restored — restores verified
  via `git diff` showing zero `MUTANT` markers before commit):
  1. `MarketplaceRegistrationService.requireAllowlistedScheme`
     short-circuited (`if (true) return;`) → 3 tests failed
     (`EstateStartupFailureTests` startup isolation,
     `declared_marketplaces_face_the_same_registration_gate_as_the_api`,
     `a_failing_entry_is_isolated_and_the_report_is_answerable_by_role`).
  2. Webhook diff check defeated (unconditional `changes.add("secret")`,
     always-update) → 2 tests failed (converged-no-op zero-writes,
     rotation idempotency).
  3. Secret value appended to the update audit detail → 1 test failed
     (`declared_secrets_are_write_only_rotated_idempotently_and_floored`,
     the ledger/report secret-absence assertions).
- Adversarial coverage: disallowed scheme, reserved catalog name, URL drift
  against a registered marketplace, `webhook` sync mode, grant for an unknown
  marketplace, blank and sub-16-character secrets — each an isolated `failed`
  entry with a ledger record and no partial row; secret values asserted absent
  from the report JSON (HTTP response) and the full ledger dump.
- The deny-by-default walk (`SVC_GW_0068`) now classifies
  `POST /api/estate/reconcile` (role-gated mutation) and `GET /api/estate`
  (auditor read); the walk's route-table completeness assertion forced the
  classification and re-verifies it on every run.
- No existing SVC test was weakened; no assertion in any pre-existing test
  changed. The four controller extractions kept identical statuses and ledger
  events, held by the existing suites (AdminTests, WebhookTests,
  AuditExportTests, RoleEnforcementTests, RolesDisabledTests — all green).
- Known limits (declared, not covered): log-output secret absence is enforced
  by construction (the secret is never passed to a log statement or the audit
  detail) and by the ledger/report assertions, not by a log-capture test;
  concurrency is a synchronized method plus database unique constraints, not
  a stress test — contention is human-scale (startup + an admin endpoint).

## Gates

### `./mvnw clean verify`

```
[INFO] Tests run: 86, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  51.144 s
```

### `(cd src/main/frontend && pnpm e2e)`

```
  8 passed (19.9s)
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
77/77 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 19 passed, 0 failed (19 items)
```

### `mkdocs build --strict`

```
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.47 seconds
```

## Commit

Final implementation commit: `1379e49700c0394c0d8d168d5db55565fdf558b6`
(gates above ran against this tree; the archive commit follows).
