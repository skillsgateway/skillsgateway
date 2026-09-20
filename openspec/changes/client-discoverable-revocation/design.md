# Design: client-discoverable-revocation

## Context

See `proposal.md — Why`. Three pieces of the current system decide most of what
follows:

- **`/git/*` is a servlet mapping.** `GitFacadeConfiguration.gitServlet()`
  registers `GitServlet` at `/git/*`, and an exact servlet mapping wins over the
  dispatcher servlet. No `@RestController` can be reached under that prefix.
- **`SecurityConfig` already has the chain shape this needs.** `publishChain`
  (`@Order(2)`, `/publish/**`) is a stateless, CSRF-exempt, PAT-over-Basic
  sibling of the facade chain. It is the template.
- **Marketplace scoping already has a stated disclosure rule.**
  `GW_AUTH_0006 — Marketplace-scoped access tokens` requires an out-of-scope
  marketplace to be answered "with an answer indistinguishable from that for a
  marketplace that does not exist", and `resolvePublished` enforces it before
  the storage lookup. This change inherits that rule rather than inventing one.

## Goals / Non-Goals

**Goals**

- A client holding a PAT can learn, for content it already has, whether the
  gateway still approves it — without holding a portal session or an `/api/**`
  credential.
- The check discloses nothing the same credential could not already learn by
  fetching.
- The answer is cheap enough to poll: one database round trip, no pack building,
  no ledger write.

**Non-Goals (design-level, beyond the proposal's scope)**

- No push. A subscriber that wants to be told when a revocation happens already
  has `marketplace.snapshot.revoked` on the lifecycle webhooks. This is the pull
  half, for the laptop that was offline.
- No client implementation. The deliverable is the endpoint and the documented
  expectation, not a gateway-supplied agent.
- No caching layer, no ETag negotiation. Revocation freshness is not a knob
  (settled in #344); a client polls as often as its operator decides.

## Decisions

### D1 — `POST /status/snapshots`, a read that is not a GET

**Why POST for a read.** The request is a list of `(marketplace, sha)` pairs. As
repeated query parameters, 40-hex SHAs exhaust a practical URL length at roughly
forty entries, and a client holding several marketplaces exceeds that. The
project already has this exact case: `POST /api/policy/playground` is annotated
in `RoleEnforcementTests` as "read-only by contract, but a POST".

**Why its own path prefix and not `/api/**`.** `/api/**` authenticates with an
OIDC session or a gateway-issued machine credential *and with nothing else*. A
git client holds neither. Putting the check there would either force a client to
hold a second credential kind or weaken `/api/**`'s authentication — and the
second is a boundary this project does not move.

*Alternative rejected:* accept PATs on `/api/**` for this one route. It makes
`/api/**`'s authentication rule conditional, which is exactly the shape ADR 0019
was careful to keep out of the facade.

### D2 — Keyed by `(marketplace, sha)`, never by bare SHA

A snapshot SHA is meaningful only inside the marketplace it was ingested for,
and the token's scope is a list of marketplace names. A bare-SHA lookup would
have to search across marketplaces, which means either answering about a
marketplace the caller's token does not permit, or silently filtering — and
silent filtering turns `unknown` into a signal about the estate. Requiring the
marketplace makes the scope check identical to the one `resolvePublished`
already performs.

### D3 — Three states, and `unknown` is not a pass

| State | Meaning |
| --- | --- |
| `approved` | Still approved. `refs/snapshots/<sha>` is still advertised, whether or not this snapshot is the marketplace's current tip. |
| `revoked` | Withdrawn, with the time. |
| `unknown` | The gateway makes no statement. |

`approved` deliberately ignores supersession: a client asking "may I keep using
what I have" is not asking "am I current". Staleness is `GW_ADOPTION`'s question
and has its own report.

`unknown` collapses four situations on purpose — a SHA the gateway never had, a
record retention removed, a marketplace outside the token's scope, and a
marketplace that does not exist. Distinguishing any of them is the disclosure
`GW_AUTH_0006` forbids. Because it collapses them, a client must treat `unknown`
as *not approved*; the requirement states that as an obligation on the answer's
meaning rather than leaving it to a client author.

*Alternative rejected:* a fourth `superseded` state. It answers a question the
adoption report already answers and would make the safe client behaviour
("anything that is not `approved` is not approved") conditional.

### D4 — A reversed revocation stays visible

`GW_APPROVAL_0017` requires that "content served over a reversed administrative
revocation is never indistinguishable from content no administrator ever
withdrew". An answer of bare `approved` for such a snapshot would be exactly
that indistinguishability, on the surface most likely to be read by a machine.
So `approved` carries a marker when the snapshot's approval stands over a
reversed administrative revocation. This is an existing obligation being honored
on a new surface, not a new one.

### D5 — The reason is not disclosed

A revocation reason is mandatory (`GW_APPROVAL_0015`) and may name an
undisclosed vulnerability, a compromised maintainer, or an internal incident.
The answer carries the *time* and not the reason. An identity entitled to the
reason reads it from the ledger through `/api/audit`, which is an auditor read.

### D6 — No ledger row; a counter instead

Stated in `proposal.md — 3`. The mechanism: a counter incremented per answered
state, so an operator can see clients checking in and — the interesting one —
that clients are still asking about content that has been withdrawn. Fixed cost,
no row per poll on the table the architecture assessment names as the one that
ends the deployment at scale.

### D7 — The request bound is a constant

A single request is capped at a fixed number of pairs, and a larger one is
refused rather than truncated — truncating would answer about a subset while
looking like a complete answer. A constant and not a `skills-gateway.*` leaf:
the config-surface analysis found that every tuning knob in the project runs on
its default in every environment that exists, and `ConfigSurfaceBudgetTests` is
a ratchet this change has no argument for raising.

### D8 — Its own deny-by-default guard test

`RoleEnforcementTests` walks the `/api/**` route table and fails when a route
appears without a deliberate classification. `/status/**` is outside that walk,
so the protection it provides does not extend here by itself. The change adds
the equivalent assertion for this chain: authentication required, scope enforced
before lookup, and a route added later to this prefix failing the suite rather
than shipping open.

## Requirement text to mint

Drafted here for review; `/opsx:apply` writes it into `docs/reqstool/` as the
SSOT. Both are new, and `GW_FACADE_0031`/`GW_FACADE_0032` are free — the highest
allocated facade id is `GW_FACADE_0030`, and no in-flight change reserves
either.

- **`GW_FACADE_0031` — A client can ask whether the content it holds is still
  approved.** Covers: the check authenticates with the same credential kinds the
  facade accepts and no other; it takes marketplace-and-commit pairs and answers
  one state per pair, in the order asked; `approved` means still approved
  whether or not superseded; `revoked` carries the time but not the reason;
  approval standing over a reversed administrative revocation is distinguishable
  from approval never withdrawn; the answer is not appended to the ledger; a
  request over the fixed bound is refused rather than partially answered.
- **`GW_FACADE_0032` — The check tells a caller nothing it could not already
  fetch.** Covers: a pair naming a marketplace outside a scoped token's list is
  answered identically to one naming a marketplace that does not exist; a commit
  the gateway never held, one no longer recorded, and one never approved are all
  answered identically; an unauthenticated request is refused; the answer carries
  no revocation reason and no fact about any marketplace not named in the request.

## Risks / Trade-offs

- **A client that never runs the check learns nothing.** → Unavoidable by the
  issue's own scoping: the gateway does not reach into client machines. Mitigated
  where it can be, by documenting the check in the guide operators already use to
  configure fleets, next to the managed-settings keys they already deploy.
- **`unknown` read as "fine"** by a client author who skipped the docs. → The
  requirement makes the meaning normative, the documented reference check treats
  `unknown` as withdrawn, and the field is named so the optimistic reading is not
  the natural one.
- **Polling cost at fleet scale.** 4,000 clients polling hourly is ~1 request/s,
  each one indexed lookup per pair. → Bounded request size (D7) and no ledger
  write (D6) keep it flat; the counter makes the load visible before it is a
  problem.
- **A new authenticated surface is a new attack surface.** → It is a read with
  no side effects, on a chain that creates no session and honours no cookie, and
  it discloses strictly less than the fetch the same credential already permits.
  The old-coder discipline applies to the scoping half specifically.
- **SHA enumeration.** → A 40-hex commit id is not guessable, and the endpoint
  answers only about pairs the caller names, so it cannot be walked.

## Migration Plan

Additive. No schema change, no migration, nothing to roll back beyond reverting
the commit — an absent endpoint is the behavior every client has today, and a
client that treats an error as `unknown` (which the documented check does) fails
closed rather than open.

## Open Questions

None that change the specs, the approach or the task breakdown. The concrete
value of the request bound (D7) and the metric's exact name (D6) are settled in
implementation against the conventions already in the code.
