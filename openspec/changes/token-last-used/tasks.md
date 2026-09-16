## 1. Traceability (SSOT first)

- [x] 1.1 Add `GW_AUTH_0031` to `docs/reqstool/requirements.yml` — the last use of a
  credential is recorded on successful authentication, approximate to the minute,
  and reported wherever the credential is listed.
- [x] 1.2 Add `SVC_GW_AUTH_0031` to `docs/reqstool/software_verification_cases.yml`
  covering both chains, the throttle, the failed-authentication negative, and the
  portal column.

## 2. Schema

- [x] 2.1 Add `src/main/resources/db/migration/V3__access_token_last_used.sql`
  adding the nullable `last_used_at TIMESTAMPTZ` with a comment saying what NULL
  means and that the ledger is the exact record. Do not edit V1 or V2.

## 3. Recording (server)

- [x] 3.1 Add `lastUsedAt` to the `AccessToken` record; fix the one explicit
  constructor call in `MachineCredentialShapeTests`.
- [x] 3.2 Add `TokenRepository.recordLastUsed(long id)` — the conditional UPDATE
  carrying the once-a-minute throttle in its WHERE clause — annotated
  `@Requirements({"GW_AUTH_0031"})`.
- [x] 3.3 Add `TokenService.recordUse(AccessToken)` delegating to it, annotated
  `@Requirements({"GW_AUTH_0031"})`.
- [x] 3.4 Call it from `PatAuthenticationProvider` on the branch that builds the
  authenticated token, and from `MachineApiAuthenticationProvider` after the
  administrative-scope filter — never before it.

## 4. API views

- [x] 4.1 Add `lastUsedAt` to `TokenController.TokenView` with a `@Schema`
  description saying NULL means never and that the value is approximate.
- [x] 4.2 Add `lastUsedAt` to `MachineTokenController.MachineCredentialView`
  likewise.
- [ ] 4.3 Regenerate `src/main/frontend/openapi.json` from `OpenApiDocsTests`'
  `target/openapi.json` and `src/api/types.gen.ts` with
  `(cd src/main/frontend && pnpm gen:api-types)`. Never hand-edit either.

## 5. Server tests (SVC_GW_AUTH_0031)

- [x] 5.1 A facade authentication with a live PAT stamps `last_used_at`, and the
  value reaches `GET /api/tokens` as `lastUsedAt`.
- [x] 5.2 A machine API authentication with a live machine credential stamps it,
  and the value reaches the machine credential listing.
- [x] 5.3 Throttle: two successful authentications in quick succession leave the
  first stamp in place; one whose stamp is older than the interval is refreshed.
- [x] 5.4 **Negative** — none of these writes `last_used_at`: an unknown secret,
  a revoked credential, an expired credential, and a fetch-only PAT presented as
  a bearer credential on the machine API chain (which resolves a row and is still
  refused).
- [x] 5.5 Annotate every one of them `@SVCs({"SVC_GW_AUTH_0031"})`.

## 6. Portal

- [x] 6.1 Add `formatRelative` to `src/lib/datetime.ts` (`Intl.RelativeTimeFormat`,
  largest sensible unit).
- [x] 6.2 Give `Timestamp` a `relative` prop and an `absent` prop, both defaulting
  to today's behaviour so no existing call site changes; the `datetime` attribute
  and the precise tooltip stay exactly as they are.
- [x] 6.3 Add the **Last used** column to `src/pages/tokens.tsx` between Created
  and Status, rendering `<Timestamp value={token.lastUsedAt} relative absent="never" />`.
  Carry the `@Requirements` JSDoc tag for `GW_AUTH_0031` on the page.
- [x] 6.4 Extend the MSW token handlers in `src/test/msw-handlers.ts` with
  `lastUsedAt` — one token used, one never used.
- [x] 6.5 Unit tests in `src/pages/tokens.test.tsx`: a used token shows the
  relative time with the exact instant on the tooltip; a never-used one shows
  "never".
- [x] 6.6 Story coverage in `src/pages/tokens.stories.tsx` for both states (axe
  runs as an error).
- [x] 6.7 Extend the token e2e in `src/main/frontend/e2e/portal.spec.ts`: the
  freshly created token reads "never", and after a real `git clone` through the
  facade with it the column no longer does. Tag `@SVCs SVC_GW_AUTH_0031`,
  snake_case title.

## 7. Docs (same PR)

- [x] 7.1 `docs/manual/reference/api/tokens.md`: `lastUsedAt` in the listing and
  machine-credential response examples and field tables, with the approximation,
  the "never" meaning and the one-time gap for credentials older than the
  migration stated plainly.
- [x] 7.2 `docs/manual/reference/portal.md`: the Last used column on the Access
  tokens page.

## 8. Design harness

- [ ] 8.1 Run `/impeccable audit` and `/impeccable harden` on the Access tokens
  page (a changed page, not a new one — no `critique`); fix or dismiss with a
  reason in the PR body.

## 9. Gates and evidence

- [ ] 9.1 `./mvnw -q spotless:apply`, then `./mvnw clean verify`.
- [ ] 9.2 `(cd src/main/frontend && pnpm test:stories)` and
  `(cd src/main/frontend && pnpm e2e)`.
- [ ] 9.3 `reqstool status local -p docs/reqstool` ends PASS.
- [ ] 9.4 `openspec validate --all --strict` and `mkdocs build --strict`.
- [ ] 9.5 Write `openspec/changes/token-last-used/evidence.md` from one final
  fresh run after the last code edit, with the commit SHA.
