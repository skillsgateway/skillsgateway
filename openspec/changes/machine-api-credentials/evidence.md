# Evidence — machine-api-credentials

**Source state:** commit `9020e63fa1c457558aec3e78169e0de32a5f3cb9` on
`feat/machine-api-credentials`, merged with `origin/main` at `f17510f`
(#138 four-eyes, #139 native enums and the Helm work are all in this tree).

**Tier:** 3 (old-coder). This change authenticates the control plane.

**Spec:** `openspec/changes/machine-api-credentials/executable-spec.md` —
every scenario as a named test with concrete inputs and expected status codes,
plus the invariants that had to survive.

**spec approval: not obtained (autonomous run).** The correlation-breaking
review never happened, so confidence is claimed correspondingly lower and the
executable spec is the artifact to review after the fact.

**Independent verification: not performed.** A declared downgrade, per the
skill's guidance for Tier 3.

---

## What was built

- `MachineApiRegistry` — every mapped `/api/**` route classified under exactly
  one named scope or on an explicit unreachable list. 21 scopes.
- `MachineApiAuthenticationFilter` / `Provider` / `Authentication` and a
  stateless `/api/**` chain at `@Order(4)`, matched additionally on
  `Authorization: Bearer`. The web chain moved to `@Order(5)` and is otherwise
  byte-for-byte unchanged (task 7.4) — the only diff to `webChain` in this branch
  is the `@Order` annotation value.
- `access_tokens.api_scopes` and `machine_owner`, with two database CHECK
  constraints: a session-derived credential can hold no API scope, and a machine
  credential must have an expiry.
- `fetch_log.actor_type`, a **native PostgreSQL enum** (`fetch_log_actor_type`)
  rather than the `TEXT` the design named — see *Deviations* below — plus an
  index on it and `ActorType`.
- `MachineTokenController` under `/api/tokens/machine/**`, admin-only regardless
  of `skills-gateway.roles.enabled`.
- Documentation: `reference/api/index.md`, `tokens.md`, `audit.md`,
  `marketplaces.md`, `adoption.md`, `policy.md`, `roles.md`, `estate.md`,
  `reference/configuration.md`, `concepts/trust-boundaries.md`,
  `architecture.md`, `guides/declarative-estate.md`.

## Requirement ids

`GW_0126`–`GW_0131` and `SVC_GW_0126`–`SVC_GW_0131` were **free** on the merged
tree. Checked before authoring: `docs/reqstool/requirements.yml` on the merged
tree carries `GW_0125` as its highest id, and neither file contained any id in
the `0126`–`0131` range. No collision.

---

## RED before GREEN

| # | Test | What was observed failing, and how |
| --- | --- | --- |
| 0.2 | `MachineApiRegistryTests.every_mapped_api_route_is_classified…` | Negative control for the guard: a throwaway `GET /api/throwaway-guard-probe` was added and the test failed with `but could not find the following elements: [GET /api/throwaway-guard-probe]`. Removing it restored `Tests run: 3, Failures: 0`. Both outputs captured. |
| 3.3 | `MachineCredentialShapeTests` (3 tests) | The three new `AccessToken` methods were first written as stubs throwing `UnsupportedOperationException`, so the RED was behavioural rather than a compile error. Observed: `Tests run: 3, Failures: 0, Errors: 3` — `java.lang.UnsupportedOperationException: not implemented at AccessToken.apiScopeList(AccessToken.java:58)` and two like it. |
| **6.5a** | `an_api_only_credential_with_an_empty_fetch_list_reaches_no_marketplace` | **The live hole, confirmed.** Written before the fix and observed failing: `org.opentest4j.AssertionFailedError: Expecting value to be false but was true` at the `permitsMarketplace("anything")` assertion. `permitsMarketplace` returned `scopeList.isEmpty() \|\| scopeList.contains(marketplace)`, so a credential holding only API scopes — which necessarily has an empty fetch list — would have authenticated on the facade and fetched the entire estate. |
| 6.5b | — | GREEN: the fetch default became conditional on `machineCredential()`. `Tests run: 5, Failures: 0`. |
| 6.5c | `an_ordinary_fetch_token_with_an_empty_fetch_list_still_reaches_every_marketplace` | Passed immediately, as pre-existing behaviour kept as regression armor. **Proved non-vacuous by mutation** (mutant 1 below), not by assertion. |
| 5.x, 10.x | `MachineCredentialLifecycleTests` | First run against a non-admin session: `Status expected:<422> but was:<403>` on four tests — the new admin-regardless-of-enforcement check firing, before the suite was given an admin session. Then `Errors: 4` with `InvalidTokenRequestException: unknown API scope 'marketplaces:regsiter'` reaching the servlet, because `MachineTokenController` had no `@ExceptionHandler`. Both fixed; `Tests run: 8, Failures: 0`. |

### The ordering violation, stated plainly

**Group 6 was written after the chain in group 7 existed, not before it.** The
tasks required the opposite order. Rather than claim an ordering that did not
happen, the equivalent evidence was produced by **deleting the chain** (removing
the `@Bean` annotation from `machineApiChain`) and rerunning the group:

- First run, chain deleted: `Tests run: 12, Failures: 3`. **Nine of the twelve
  still passed** — because every refusal in them is one-sided, and with no
  machine chain at all the session chain answers 401 to anything without a
  cookie. A one-sided invariant cannot catch a fail-closed defect.
- Each refusal was then paired with a **positive control** — the same route,
  reached by a properly scoped credential.
- Second run, chain deleted: `Tests run: 12, Failures: 9`. The three that still
  pass are correct as-is: two assert facade behaviour and one asserts the session
  chain is unchanged.

This is recorded as a process deviation, not presented as compliance.

---

## The mutation gauntlet

`openspec/changes/machine-api-credentials/mutation-run.sh` (in the repo, one
entry-point command). Seven hand-chosen mutants against the three pieces of
logic whose failure would be a real hole — the scope predicate, the allowlist
lookup, and the chain matcher — each a plausible bug rather than a syntactic
tweak. The script fails closed: an unapplied patch aborts, and it refuses to
exit while any mutated file still differs from HEAD.

```
$ openspec/changes/machine-api-credentials/mutation-run.sh
killed    fetch default becomes unconditional again
killed    permitsApiScope grants any scope once one is held
killed    provider drops the non-empty API scope precondition
killed    cookie alongside a bearer credential is tolerated
killed    matcher stops recognising a bare Bearer scheme
killed    allowlist falls through to permitAll
killed    per-route scope requirement dropped

mutants killed: 7   survived: 0
```

### The survivor, and the defect it found

Mutant 5 **survived two runs**. The suite could not tell whether the chain
matcher recognised an `Authorization: Bearer` header with nothing after it — not
even a trailing space — because both the machine chain and the session chain
answer a bare 401, so no assertion on status or body could distinguish them.

The first attempted fix (asserting the bare scheme's 401 body equals the
others') did not kill it, for exactly that reason. The distinction is observable
in **one** place: under `skills-gateway.dev-insecure-auth=true` the session chain
permits everything, so falling through to it answers **200**. The test moved to
`DevAuthTests`, and it immediately failed with `Status expected:<401> but
was:<200>` — which turned out to be a **real defect on the branch**, not the
mutant: a mutant applied on disk had been committed by an interleaved `git add
-A` in `26cbc56`, leaving `|| false` in the matcher.

Fixed in `9020e63`, together with the script hardening that makes it impossible
to repeat: the gauntlet now backs the files up **by content** instead of relying
on `git checkout --`, which would have restored a mid-run commit *to* the mutant.

This is the gauntlet doing the job it exists for, and it is reported rather than
tidied away.

---

## Checker negative controls

| Checker | Proved it can fail | Result |
| --- | --- | --- |
| `MachineApiRegistryTests` (the allowlist guard) | Added a throwaway `GET /api/throwaway-guard-probe` | Failed, naming exactly that route. Removed; green. |
| `mutation-run.sh` | Committed a mutant on disk (accidentally, see above) | The rerun reported `SURVIVED`, and the hardened script's post-run `git diff --quiet` check now exits 3 rather than passing. |

**What these buy, precisely:** each proves one known-bad case reaches the
checker's failure path. Neither proves the checker recognises every violation of
the rule it serves. The registry guard checks that every route is *classified*;
it cannot check that a route is classified *correctly* — that a new act of human
judgement was put on the unreachable list rather than under a scope. That
remains a human review obligation, and it is stated in the registry's own
javadoc rather than left to be inferred from the guard's existence.

---

## Deviations from the design, and why

1. **`fetch_log.actor_type` is a native PostgreSQL enum, not `TEXT`.** The design
   said `TEXT`. `CLAUDE.md` and `GW_0125` — merged into `main` as #139, *after*
   this design was written — require every closed set of enumerated values to be
   a database type. Three values, closed set: `CREATE TYPE fetch_log_actor_type
   AS ENUM ('human','machine','system')`. This satisfies the design's intent
   (a queryable, non-parsed actor kind) under the newer repo-wide rule.
2. **`GET /api/snapshots/{id}/four-eyes` is classified `snapshots:read`.** The
   endpoint did not exist when the design's controller inventory was taken; it
   arrived with #138. It reports whether a second reviewer is required and who
   the first was — a read of the same evidence surface as `/vetting`, publishing
   nothing. Approval itself stays unreachable, which is what keeps it a read.
3. **Five system actor names are declared, not three.** The design named
   `config-reconciler`, `scheduler` and `system`. The codebase also has
   `SyncService.WEBHOOK_ACTOR` (`webhook`) and `RevetService.SWEEP_ACTOR`
   (`revet-policy`). All five are declared in `AdminAuditLogger.SYSTEM_ACTORS`,
   referencing the existing constants so a rename is a compile error.
4. **`actor_type` is derived in `AdminAuditLogger` from a declared set of the
   gateway's own actor names, rather than threaded as a parameter from ~25 call
   sites.** The design's task 4.3 asked for explicit declaration at the call
   sites; the actor is a `String` threaded deep through service signatures
   (`ingest(marketplace, actor)`), so declaring it there would have meant a
   second parameter through every one. The comparison still happens, but **once**,
   in the one place that types a ledger entry — and no ledger *consumer* compares
   strings any more, which is the property the decision was actually about.
   `recordAs(ActorType, …)` exists for a caller that wants to state it directly.
5. **Task group 0 shipped as commits on this branch, not a separate PR.** It is
   genuinely separable — `c87f53d` adds only the registry and its guard, and is
   green on its own — but the owner merges by hand, so a stacked PR would have
   added overhead without adding review. The commit message says so.

## Open questions, deliberately left open

Design decision 6c's two questions are **not decided** here, and are implemented
as the design currently states:

1. **Should `audit-sinks:write` be estate-only, like role grants?**
   `DELETE /api/audit/sinks/{id}` and `PUT …/cursor` let a stolen credential stop
   or rewind the ledger's export to a SIEM, which is closer to tampering with
   audit visibility than to configuration. Implemented as proposed (reachable);
   the alternative is moving deletion and cursor rewrite to the unreachable table
   while leaving creation reachable.
2. **`POST /api/policy/playground` sits under `policy:read`** because it
   evaluates a policy and persists nothing. Implemented as stated; recorded here
   and in `reference/api/policy.md` so the shape is not rediscovered.

The design's own three open questions (read-logging beyond `/api/roles`, the
absence of a credential-introspection endpoint, and whether `estate:reconcile`
and `catalog:rebuild` should be one scope) are likewise untouched.

## Known limits

- **The allowlist guard checks classification, not correctness.** See the
  negative-control note above.
- **`actor_type` on facade fetches is imperfect by construction.** A fetch-only
  PAT in a CI variable records as `human`. Argued in the design and documented in
  `reference/api/audit.md` rather than glossed.
- **No portal UI.** The first machine credential is still minted by a person
  from a browser session, which is the workaround this capability set out to
  reduce. Stated in the proposal and in the docs.
- **The `/api/**` session-cookie CSRF exemption is untouched** and still
  deferred. The machine chain does not deepen it: it honours no cookie and
  refuses a request carrying one, so nothing new depends on the exemption.

---

## Contract clause mapping

| Clause | Verified by |
| --- | --- |
| GW_0126 — API scope is its own dimension; empty grants nothing; unknown and wildcard values refused | `MachineCredentialShapeTests` (3), `MachineCredentialLifecycleTests.an_unknown_api_scope_is_refused_at_issue_time`, `.there_is_no_wildcard_and_an_empty_scope_list_grants_nothing` |
| GW_0127 — only an API-scoped bearer credential reaches `/api/**`; symmetric; no cookie; no session; dev mode unaffected | `MachineCredentialNegativeTests` (12), `DevAuthTests.dev_insecure_auth_does_not_open_the_bearer_path`, `MachineCredentialLifecycleTests.a_session_derived_credential_can_never_hold_an_api_scope` |
| GW_0128 — explicit actor kind, denormalised, credential id, roles-read logged uniformly, ledger read not logged | `MachineLedgerTests` (7) |
| GW_0129 — every route classified; allowlist excludes judgement, retraction, granting and minting; independent of `roles.enabled` | `MachineApiRegistryTests` (3), `MachineApiScopeTests` (8) |
| GW_0130 — no claim-derived role; intersection of scope and role; grants API unreachable; estate is the only route; admin required regardless of the flag | `MachineCredentialAdminTests` (2), `MachineRoleIntersectionTests` (3) |
| GW_0131 — mandatory expiry under a cap that applies with `max-ttl` unset; rotation preserves everything; central administration | `MachineCredentialLifecycleTests` (8) |
| **Invariant** SVC_GW_0011 — the browser session reaches `/api/**` unchanged | `AuthTests.webAccessRequiresOidcSession`, `MachineCredentialNegativeTests.a_browser_session_without_a_bearer_header_reaches_the_api_exactly_as_before` |
| **Invariant** SVC_GW_0012 — the facade still requires a valid PAT | `AuthTests.gitFacadeRequiresValidPersonalAccessToken` |
| **Invariant** SVC_GW_0064 — an empty fetch list on a non-machine token still grants every marketplace | `MachineCredentialShapeTests.an_ordinary_fetch_token_with_an_empty_fetch_list_still_reaches_every_marketplace`, `MachineCredentialNegativeTests.an_ordinary_fetch_token_with_an_empty_scope_list_still_clones_every_marketplace` |
| **Invariant** SVC_GW_0102 — push semantics unchanged | `HostedPushTests` (unmodified), `MachineCredentialNegativeTests.a_push_scoped_token_reaches_no_api_endpoint` |
| **Invariant** SVC_GW_0104 — session-derived credentials unchanged | `SessionCredentialTests`, `SessionCredentialExpiryTests` (both unmodified) |
| **Invariant** — no existing SVC test weakened or deleted (task 13.3) | See below |

### Task 13.3 — no SVC test was weakened

Three existing test files were touched, all **additively**:

- `AuthTests` — the token view's closed field enumeration gained `"apiScopes"`.
  Nothing was removed and no assertion was broadened; the enumeration is still
  exact, which is what would catch `apiScopes` becoming populated on a credential
  nobody provisioned as a machine one.
- `RoleEnforcementTests` — the four new `/api/tokens/machine` routes were added
  to the route-table walk (a new `ALWAYS_ADMIN_MUTATIONS` set, walked alongside
  the existing one) and the auditor walk gained an explicit 403 assertion for
  `GET /api/tokens/machine`. Assertions were **added**, not relaxed.
- `DevAuthTests` — one new test. The existing one is untouched.

`git diff origin/main -- src/test` shows no deleted test method and no `@Disabled`.

---

## The gates

One fresh run of all six, in order, after the last code edit, at
`9020e63fa1c457558aec3e78169e0de32a5f3cb9`.


### 1. `./mvnw clean verify`

```
[INFO] Tests run: 256, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:17 min
```

256 tests, **0 failures, 0 errors**. Spotless, Checkstyle, the CycloneDX SBOM,
the UI gates and the packaged jar are all inside this run. 40 of those tests are
new in this change.

### 2. `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
   Start at  16:05:32
   Duration  2.97s (transform 0ms, setup 1.74s, import 672ms, tests 809ms, environment 0ms)
```

### 3. `(cd src/main/frontend && pnpm e2e)`

```
  13 passed (32.5s)
```

13 Playwright tests in real chromium against the packaged jar and a mock OIDC
identity provider. No e2e test was added: this change ships no portal UI.

### 4. `reqstool status local -p docs/reqstool`

```
119/119 complete · 0 incomplete · PASS
```

Up from 113/113 — the six new requirements are traced. The gate exits 0 even
when it prints `FAIL`, so the last line is what was read.

### 5. `openspec validate --all --strict`

```
Totals: 27 passed, 0 failed (27 items)
```

### 6. `mkdocs build --strict`

```
INFO    -  Documentation built in 0.67 seconds
```

No warnings. `--strict` turns a broken internal link or an unlisted page into a
failure, which is what makes it usable as a gate.

### Additionally: the API compatibility gate

The contract change is **additive**, so the breaking-change gate stays green and
the PR title needs no `!`:

```
$ oasdiff breaking <origin/main openapi.json> <this branch's openapi.json>
No breaking changes to report, but the specs are different.
```

`src/main/frontend/openapi.json` and `src/api/types.gen.ts` were regenerated from
`target/openapi.json`, so `OpenApiContractTests` (which fails the build when the
committed document is not the one the gateway serves) passes inside gate 1.

---

## Environment

- macOS (darwin 25.3.0), Java 25, Maven wrapper, Podman providing
  `/var/run/docker.sock`.
- `TESTCONTAINERS_RYUK_DISABLED=true` throughout, as this environment requires.
- PostgreSQL via **Arconia dev services** (`arconia-dev-services-postgresql`),
  not a hand-rolled Testcontainers container. **No new dependency was added by
  this change**, as the spec's setup plan stated.
