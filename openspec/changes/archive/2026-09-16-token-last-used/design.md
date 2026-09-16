## Context

See proposal.md — Why. The relevant current state:

- Both credential chains resolve a secret through one method,
  `TokenService.authenticate(String)`, which hashes the presented value and asks
  `TokenRepository.findActiveByHash` for a live row. Revocation and expiry are
  already applied by that query (GW_AUTH_0007 — *Expiry is decided at
  authentication time*).
- That method is **not** the point at which authentication succeeds. The machine
  API chain calls it and then rejects the result unless the credential holds
  administrative scope (`MachineApiAuthenticationProvider`, GW_AUTH_0021 — *A
  session-derived credential never holds administrative scope*). A facade token
  presented as a bearer credential therefore resolves a row and is still refused.
- `access_tokens` is written on issue, rotation and revocation, and read on every
  facade request. Nothing writes to it on the read path today.
- The fetch ledger (`fetch_log`) already records every facade request with the
  token id that authenticated it (GW_AUTH_0009 — *Per-token attribution on the
  fetch ledger*).

## Goals / Non-Goals

**Goals:**

- One place records last use, and it is reached only when authentication has
  actually succeeded on the chain that is authenticating.
- The read path costs one extra statement that normally matches no row.
- The portal answers "is anybody still using this?" from the list, without a
  ledger query.

**Non-Goals:**

- Precision. `last_used_at` is deliberately approximate to the minute; anything
  that needs the exact record has the ledger, which is unchanged.
- Last use of anything but a credential. Sessions, marketplaces and snapshots
  are out of scope.
- An estate object. Tokens are API-only by design — a secret returned exactly
  once cannot round-trip through a declarative document — and last use is
  observed state rather than configuration, so `skills-gateway.estate.*` gains
  nothing here. This is the "deliberately API-only" case CLAUDE.md's declarative
  estate obligation allows, stated rather than assumed.

## Decisions

### Recording happens at each provider, not inside `authenticate`

`TokenService.authenticate` is the tempting single choke point, and it is the
wrong one: the machine API chain calls it before deciding, so a fetch token
presented as a bearer credential would have its last use stamped by a request
that was refused. `last_used_at` would then be evidence of an acceptance that
never happened — which is exactly the negative guarantee this change has to
hold.

Instead `TokenService.recordUse(AccessToken)` is called by
`PatAuthenticationProvider` and `MachineApiAuthenticationProvider`, each on the
branch where it has already decided to authenticate. Two call sites rather than
one, in exchange for a guarantee that reads off the code: the stamp is written
where the `Authentication` is built and nowhere else.

*Alternative considered:* a Spring Security `AuthenticationSuccessEvent`
listener. Rejected — it is asynchronous relative to the request in ways that
complicate the test, it fires for OIDC session logins too (which have no token
row), and it puts the guarantee one indirection away from the code that makes
the decision.

### The once-a-minute throttle is the UPDATE's own WHERE clause

```sql
UPDATE access_tokens SET last_used_at = :now
 WHERE id = :id AND (last_used_at IS NULL OR last_used_at <= :threshold)
```

with `threshold = now - 1 minute`. No read before the write, no in-memory cache
to keep coherent across instances, and no lost-update window: concurrent
requests for the same token race on one row and the loser updates nothing. A
busy token costs one statement matching zero rows per request; a quiet one costs
one row write per minute of use.

*Alternatives considered:* a per-instance `ConcurrentHashMap` of last-write
times, which is cheaper still but wrong the moment the gateway runs more than
one replica (each would write once a minute *each*) and loses everything on
restart; and an unconditional write, which turns every facade request into a row
write and a WAL record for a value nobody reads at that resolution.

### The interval is a constant, not a property

`Duration.ofMinutes(1)`, named in `TokenRepository`. A setting here would be a
knob with no operational question behind it: nothing downstream reads
`last_used_at` at finer resolution, and an operator who needs the exact record
has the ledger. `skills-gateway.*` and the configuration reference are unchanged.

### `lastUsedAt` is a new component on `AccessToken`, mapped by column name

`JdbcClient`'s record mapping is by column name, so the record gains
`lastUsedAt` and every `SELECT *` picks it up with no query change. The record's
constructor is only called explicitly in one test helper
(`MachineCredentialShapeTests`), so the widening is contained.

### Additive on the API contract

A new nullable field on two response records. No path prefix moves, nothing is
removed or retyped, so the change is additive within the major (see
`docs/manual/reference/compatibility.md` — "The API contract").
`src/main/frontend/openapi.json` is regenerated from `OpenApiDocsTests`'
`target/openapi.json` and `src/api/types.gen.ts` from it with
`pnpm gen:api-types`; neither is hand-edited, and `OpenApiContractTests` fails
the build if the committed document drifts from the served one.

### The portal reuses `Timestamp` with a relative mode

`Timestamp` already keeps the wire value in `<time datetime>` and the precise
local rendering in `title`. It gains a `relative` prop that swaps the visible
text for an `Intl.RelativeTimeFormat` rendering while leaving both of those
untouched, and an `absent` prop so this column can say "never" where the ledger
pages say "—". No second timestamp component, and every existing call site is
unaffected because both props default to today's behaviour.

*Alternative considered:* formatting in `tokens.tsx`. Rejected — the tooltip and
the machine-readable `datetime` attribute are the part that must not be
reinvented per page, which is the reason `Timestamp` exists.

## Risks / Trade-offs

- **The value understates use by up to a minute, and an operator could read a
  "never" as proof a token was never presented.** → The tooltip and the docs both
  say the value is approximate and that the ledger is the exact record; "never"
  means *no successful authentication has been recorded*, which the API page
  states in those words. A credential that existed before this migration also
  reads "never" until its next use — called out in the docs as the one-time
  backfill gap, because there is no honest backfill: the ledger has token ids for
  facade fetches but the machine API chain never wrote one.
- **A write on the read path.** → It is one statement, usually matching no row,
  outside any transaction the facade holds open, and it is not on the git pack
  path — it happens once at authentication, not per object. The throttle bounds
  the write rate per token at 1/min regardless of request rate.
- **Two call sites can drift** — a third authentication chain could be added and
  forget to record. → The tests assert the recording per chain rather than per
  method, so a new chain that does not record is a chain with no test asserting
  it does; and the two existing call sites sit on the line that builds the
  `Authentication`, which is hard to add without noticing.

## Migration Plan

`V3__access_token_last_used.sql` adds one nullable column. Nothing is backfilled
and nothing is rewritten, so the migration is instantaneous on any table size and
the rollback is dropping the column — no data is lost that was not already
derivable from the ledger. A gateway running the previous version against the
migrated schema ignores the column entirely.
