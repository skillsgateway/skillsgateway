## Why

A credential's listing says when it was created, what it may reach and when it
expires — but nothing about whether anybody still uses it. Revocation and
rotation are decisions about exactly that: an operator looking at a page of
tokens cannot tell the one a pipeline authenticates with every ten minutes from
the one a person minted last spring and forgot. The fetch ledger holds the
answer, but only as history that has to be queried and joined; the question
"is this credential live?" belongs on the credential.

Resolves GitHub issue #394.

## What Changes

- `access_tokens` gains a nullable `last_used_at`, in a **new** Flyway migration
  (`V3`); `V1__init.sql` and `V2__rename_connector_to_vetter.sql` are not edited.
- Successful authentication of a credential — on the git facade (PAT) and on the
  machine API (bearer) alike — records the moment. The write is throttled to at
  most once per token per minute so the hot path stays a single statement that
  usually matches no row; the fetch ledger remains the exact, per-request record.
- A **failed** authentication records nothing. This is the negative guarantee of
  the change: `last_used_at` must never become evidence that an unknown secret,
  a revoked credential, an expired one, or a fetch token presented on the machine
  API chain was accepted.
- `lastUsedAt` is added to `GET /api/tokens` (`TokenView`) and to the machine
  credential listing (`MachineCredentialView`). Additive within the major: a new
  nullable field on a response, no path move, no removal.
- The portal's Access tokens page gains a **Last used** column showing the
  relative time ("3 hours ago") with the exact instant on the tooltip, and
  "never" where the credential has not authenticated. The existing `Timestamp`
  component gains a relative mode rather than a second timestamp component
  appearing beside it.
- Documentation: `docs/manual/reference/api/tokens.md` and
  `docs/manual/reference/portal.md` in the same PR.

Not in scope: last use is **not** an estate object. Tokens are deliberately
API-only — a secret that can only be read once cannot round-trip through a
declarative estate document — and last use is observed state rather than
configuration, so `skills-gateway.estate.*` is unchanged. The design says so
explicitly, as CLAUDE.md requires of any change adding API-managed state.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `token-lifecycle`: adds `GW_AUTH_0031` — the last use of a credential is
  recorded on successful authentication and reported wherever the credential is
  listed.

**Requirement id allocation.** `GW_AUTH_0031` is the next free id: the highest
allocated across `docs/reqstool/requirements.yml`, every in-flight
`openspec/changes/*/` and every remote branch was `GW_AUTH_0030`
(csrf-on-the-session-surface). Issue #392 (git-client authentication behind SSO)
is being specified concurrently and allocates in the same range; if it takes
`GW_AUTH_0031` first, this change renumbers rather than the other way round.
`SVC_GW_AUTH_0031` verifies it.

## Impact

- Schema: `src/main/resources/db/migration/V3__access_token_last_used.sql`.
- Java: `AccessToken`, `TokenRepository`, `TokenService`,
  `PatAuthenticationProvider`, `MachineApiAuthenticationProvider`,
  `TokenController.TokenView`, `MachineTokenController.MachineCredentialView`.
- API contract: `src/main/frontend/openapi.json` regenerated from
  `OpenApiDocsTests`, `src/api/types.gen.ts` regenerated with `pnpm gen:api-types`.
- Portal: `src/components/timestamp.tsx`, `src/lib/datetime.ts`,
  `src/pages/tokens.tsx`, its MSW handlers, unit tests, stories, and the token
  e2e spec.
- Docs: `docs/manual/reference/api/tokens.md`, `docs/manual/reference/portal.md`.
- Traceability: `docs/reqstool/requirements.yml`,
  `docs/reqstool/software_verification_cases.yml`.
