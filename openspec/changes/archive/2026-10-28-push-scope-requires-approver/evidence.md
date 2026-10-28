# Evidence: push-scope-requires-approver

Source state: `776fad64f9d76c3af18b9180eb9cbc19cc4fb492` (branch
`fix/push-scope-authority`, on `origin/main` `a662f485`). Every result below is
from one fresh run after the last code edit, on 2026-10-28.

Tier 3 (auth, trust boundary). Spec approval: obtained from the owner before
implementation. Independent verification: not performed. It is a declared
downgrade, chosen to keep the exposure window short.

## Spec → tests

| Scenario (SVC_GW_FACADE_0035) | Test | Seen failing first |
|---|---|---|
| A no-role session asking for a push scope is refused and holds no token or ledger row | `PushScopeMintingTests.a_session_holding_no_role_is_refused_a_push_scope_and_holds_no_token` | yes: `Status expected:<403> but was:<201>` |
| An approver of A asking for A and B, in either order, is refused and holds no token | `…an_approver_naming_a_marketplace_they_do_not_approve_is_refused_the_whole_request` | yes: `Status expected:<403> but was:<201>` |
| An approver of A is given A, and that token publishes over `/publish` | `…an_approver_is_given_a_push_scope_for_their_marketplace_and_it_publishes` | no, it held already; proved non-vacuous by M3 |
| An administrator is given any hosted marketplace | `…an_administrator_is_given_a_push_scope_for_any_hosted_marketplace` | no, it held already; regression armor |
| A no-role session still mints a token without a push scope | `…a_session_holding_no_role_still_mints_a_token_without_a_push_scope` | no, it held already; regression armor |
| A claim-derived administrator is given a push scope | `ClaimMappedPushScopeTests.a_claim_derived_administrator_is_given_a_push_scope` | no, it held already; proved non-vacuous by M4 |

Invariants kept: `SVC_GW_FACADE_0007` (`HostedPushTests`, 8 tests) and
`RoleEnforcementTests` were unchanged and stayed green.
`ContextBudgetTests` and `ConfigSurfaceBudgetTests` were unchanged and passed.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Full suite, Spotless, Checkstyle, UI gate, jar | `./mvnw clean verify` | `Tests run: 1108, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · `Total time: 08:58 min` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 21 passed (21)` · `Tests 111 passed (111)` |
| e2e | `(cd src/main/frontend && pnpm e2e)` | `27 passed (1.8m)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `343/343 complete · 0 incomplete · PASS` |
| OpenSpec | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 2.35 seconds`; one INFO anchor note in `reference/api/tokens.md` predates this change |
| Mutation | `openspec/changes/archive/2026-10-28-push-scope-requires-approver/mutants.sh` | `4 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| API contract | `OpenApiContractTests` (in verify) | green after regenerating `openapi.json` and `types.gen.ts`; the change is additive (one `403` response) |
| Coverage on changed lines | not measured by a tool; every branch of the new check is killed by a mutant | — |
| Property-based tests | skipped: the input is a small closed set of caller standings, enumerated above | — |
| Supply chain | no dependency added or changed | — |

Mutation (all against `TokenController.create`):

```
M1-no-check killed
M2-first-scope-only killed
M3-admin-only killed
M4-stored-roles-only killed
4 mutants run, all killed
```

## What went wrong, and how it was resolved

- The first `pnpm test:stories` run failed to import 4 of 21 story files
  (`Failed to fetch dynamically imported module`), while all 87 tests that
  loaded passed. This is the known Storybook harness flake. After deleting
  `node_modules/.cache/storybook` and `node_modules/.vite`, one run warmed the
  cache, and the run recorded above passed in full. This change touches no
  component; the only frontend file it changes is the generated `types.gen.ts`.

## Known limits

- A token keeps its push scope after its owner loses the role. Nothing
  re-checks standing when the token is used. This is out of scope by design
  (design.md, Non-Goals).
- Tokens minted before this change are not revisited (design.md, Risks).
