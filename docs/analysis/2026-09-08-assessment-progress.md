# Assessment progress tracker

Working notes for the run through
[`2026-09-08-architecture-assessment.md`](2026-09-08-architecture-assessment.md).
Untracked, like the assessment itself. **Update this after every PR** — it is
what makes the session resumable after a context clear.

Owner's standing instructions for this run: **run the whole list, in the
assessment's §5 order but security first; divide into PRs as suitable; think
KISS; do not wait for approval between PRs.**

---

## Status

| # | Step | What | PR | State |
| --- | --- | --- | --- | --- |
| 1 | F6 | Gate the blast-radius report behind approver scoping | [#335](https://github.com/skillsgateway/skillsgateway/pull/335) | **merged** |
| 2 | F4 | CSRF token on the cookie-authenticated surface | [#336](https://github.com/skillsgateway/skillsgateway/pull/336) | **merged** |
| 3 | F5 | Derive `GET` routes; classify every read | [#337](https://github.com/skillsgateway/skillsgateway/pull/337) | **open** |
| 4 | §2.1 | Catalog name collisions — the substitution defect | [#338](https://github.com/skillsgateway/skillsgateway/pull/338) | **open** |
| 5 | D3 | Backlog triage: archive `invocation-adoption-metrics` as a "no"; park `corpus-aware-vetting` and `estate-import-export` | — | not started |
| 6 | F3 | Reverse publish/record order; build the startup reconciliation its javadoc already claims | — | not started |
| 7 | F2 | Postgres advisory lock per sweep, uniformly | — | not started |
| 8 | F8/§4 | Collapse the 38 never-set knobs to constants; harden the 9 ingestion budgets | — | not started |
| 9 | F7(1,3) | Ledger honesty + partitioning | — | not started |
| 10 | F1, D4, D5 | Freeze object-store; stop rule in `CLAUDE.md`; one-page capability map | — | not started |

**D1 is now satisfied** — F4 and F6 are both merged, so `docs/analysis/` may be
committed and the assessment published whenever the owner wants. Ask before
doing it; publishing was explicitly the owner's call.

---

## Decisions taken (do not relitigate)

- **F6 guard is `requireApproverOfSnapshot`, not `requireAuditor`.** Same guard
  as the `POST .../revet` beside it; keeps the portal's "Already fetched by"
  panel working for the approver working an incident. Auditors keep the facts
  via `/api/audit`.
- **`SameSite=lax`, not the `strict` the assessment recommended.** `strict`
  withholds the session cookie on the identity provider's redirect back and
  breaks login. e2e cannot catch it — mock IdP and gateway are both `localhost`,
  so the callback is same-site under test and cross-site in production.
- **A security correction narrows `/api/**` in place** rather than moving the
  path prefix; recorded in `docs/manual/reference/compatibility.md`. oasdiff
  cannot see such a narrowing, so it is review's job.
- **F3 (step 6): build the reconciliation, do NOT reverse the publish/record
  order.** The assessment itself says the reconciliation "makes the reordering
  optional". Scoping found the reversal contradicts three clauses of
  `GW_APPROVAL_0012` and carries eight distinct risks — replay dragging the tip
  backwards, a served-but-unrecorded window visible to consumers, `undecide()`
  becoming dead but annotated code, and `GW_FACADE_0019`'s stated invariant
  ("approval writes the row before it publishes") becoming false. The startup
  check its javadoc claims genuinely does not exist — verified against all five
  startup components. Build it as a scheduled sweep, not startup-only.
- **F2 (step 7): use a lease table, NOT `pg_try_advisory_lock`.** Advisory locks
  are session-scoped; with HikariCP the lock returns to the pool held, and a
  `finally`-unlock has no guarantee of the same connection — a leaked sweep lock
  disables that sweep silently for up to 30 minutes. A `sweep_leases` table
  claimed with the exact conditional-`UPDATE` shape of
  `WebhookDeliveryRepository.claim()` is house idiom, pool-safe and crash-safe.
  Lock the `@Scheduled` wrapper only — every test calls the service method, so
  they stay green by construction.
- **D3 is WRONG about `invocation-adoption-metrics`.** The assessment says
  archive it "as a decision not to build". ADR 0016 explicitly **rejected** doing
  nothing ("5. Do nothing. **Rejected**") and chose to build the *presence* half;
  only 2 of its 27 tasks encode the refusal. The finished "no" is ADR 0016
  itself, which stands alone. It is **parked**, like the other three, blocked on
  ADR acceptance.
- **F8/§4 (step 8): its "best-value item" is the worst.** §4 says turn the nine
  `ingestion.external-sources.budgets.*` into constants. **Nine requirements
  (GW_INGEST_0026 and .1–.8) say "shall bound, *by configuration*"**, and three
  tests exercise the limits — `max-blob-bytes`, `max-received-bytes` and
  `deadline` would all break, one needing a 50 MB fixture stream per CI run. The
  honest version is a **ceiling, not a constant**: keep them settable, clamp in
  the compact constructor so an operator can lower but never raise past what the
  gateway will defend. Realistic outcome is **127 → 104 leaves, not 89** — the
  gap is 15 leaves requirements require. Highest-value item is actually a
  **leaf-count ratchet test** (nothing today notices the surface growing), then
  the 9 dead `@Scheduled` placeholder components that duplicate their defaults.
  Split into 4 PRs; `cache.ref-freshness` is a genuine security hardening and
  belongs in its own PR titled as one.
- **#336 shipped with `!` (major).** CI did not require it; it was a deliberate
  version signal because the change adds a protocol requirement to every
  mutating session call. #335 did not, because it only narrowed authorization
  for callers never entitled.

## Known-open follow-ups found on the way (not yet done)

- **The `skills-gateway.roles.enabled` switch was removed (#210), but ~12 places
  still speak as if enforcement were conditional** — 10 × `403 | Enforcement is
  enabled and …` table rows in `reference/api/{policy,estate,roles,marketplaces}.md`,
  2 × "While role enforcement is enabled" in `reference/portal.md`, plus three
  `@ApiResponse` strings and `GW_AUTH_0010`'s own description. A small docs-only
  PR. Good candidate to fold in beside step 8.
- `guides/delegated-administration.md` "snapshot contents" ambiguity — **fixed
  in step 3**.

---

## Environment gotchas (all cost real time; do not rediscover)

- **Run the gates in the FOREGROUND.** Three background `./mvnw clean verify`
  runs were killed by the harness memory watchdog at the CycloneDX step while
  21 GiB was free. Foreground succeeds in ~7 min. Use
  `MAVEN_OPTS="-Xmx3g" timeout 570 ./mvnw clean verify > log 2>&1` inside a Bash
  call with `timeout: 600000`. Run each gate as its own call — the whole chain
  does not fit in the 10-minute ceiling.
- **A killed run leaks Testcontainers.** Clean up before retrying:
  `docker rm -f $(docker ps -q --filter label=org.testcontainers=true)`.
- **Regenerate the API types with `pnpm gen:api-types`**, never
  `pnpm exec openapi-typescript` — the latter resolves the workspace's
  TypeScript 7 and dies. The skill said the wrong thing; corrected in #335.
- **Playwright browsers**: 1.63.0 wants chromium build 1243. Installed
  2026-09-09; recorded with an `undo.sh` in `~/agents/skillsgateway/env/`.
- **`ContextBudgetTests` caps distinct Spring contexts at 32.** Reuse an
  existing `@SpringBootTest` property set rather than raising the ratchet.
- **`SecurityMockMvcRequestPostProcessors.csrf()` permanently swaps the app's
  `CookieCsrfTokenRepository`** on the cached context's filter chain. Anything
  asserting on the real cookie needs its own context and no MockMvc.

## The gate sequence

```bash
MAVEN_OPTS="-Xmx3g" timeout 570 ./mvnw clean verify   # foreground, own call
(cd src/main/frontend && timeout 570 pnpm test:stories)
(cd src/main/frontend && timeout 570 pnpm e2e)
reqstool status local -p docs/reqstool                # must end PASS
openspec validate --all --strict
mkdocs build --strict
```

Then: `evidence.md` in the change dir, commit the code, commit the evidence,
`openspec archive <name> --yes`, commit the archive, push, open the PR with the
template at `.github/pull_request_template.md`.
