# Proposal: client-discoverable-revocation

## Why

`administrative-revocation` gave the gateway a door to withdraw approved
content, and named the half it did not build:

> **Out of scope**: telling clients that already hold the content (#427). This
> change makes the gateway stop serving it; making a client notice is a separate
> problem with its own design.

This is that problem. When a snapshot is revoked the gateway does everything on
its own side correctly — it leaves the served refs, the ledger records it,
`marketplace.snapshot.revoked` fires, and
`GW_FACADE_0022 — Revocation propagates to the mirror` withdraws it from the
forge. None of that reaches the laptop that cloned it last week. The skills are
on disk and nothing ever asks whether what is held is still approved.

Revocation exists for the case where approved content turns out to be harmful.
In exactly that case the copies already distributed **are** the blast radius. A
withdrawal that only removes content from future fetches is the difference
between *we withdrew it* and *they stopped using it*.

**Half the mechanism is already there and nobody has been told.**
`GitStorage.unpublish` removes `refs/snapshots/<sha>` along with the served tip,
and `GitFacadeConfiguration.SERVED_REFS` advertises `refs/snapshots/*`
deliberately. So `git ls-remote <url> refs/snapshots/<sha>` already answers
today — but only with presence or absence, which cannot tell a revocation from a
SHA this gateway never had, carries no time, and appears in no documentation a
client operator would find.

## What Changes

### 1. A PAT-authenticated check a client can run

`POST /status/snapshots`. The caller sends the `(marketplace, sha)` pairs it
holds; the gateway answers one state per pair:

- **`approved`** — still approved, still fetchable. Superseded by a later
  approval is still `approved`: `refs/snapshots/<sha>` stays advertised, and the
  question asked is "may I keep using this", not "is this the tip".
- **`revoked`** — withdrawn, with the time it happened.
- **`unknown`** — the gateway makes no statement.

**`unknown` is not a pass**, and the requirement says so rather than leaving it
to a client author's optimism. It is the answer for a SHA the gateway never had,
for one whose record retention removed, for a marketplace the caller's token is
not scoped to, and for a marketplace that does not exist — deliberately the same
answer for all four.

The credential is the one the client already has. This is a sibling of the
`/publish/**` chain in `SecurityConfig`, not a mode on the facade: same PAT over
Basic, `STATELESS`, no cookie, no session, no CSRF token — and the same
`GW_AUTH_0006 — Marketplace-scoped access tokens` scoping, enforced before the
lookup so a scoped token cannot use the check as a directory of what else the
gateway governs.

It cannot live under `/git/**`: that path is a servlet mapping owned by
`GitServlet`, and an exact servlet mapping wins over the dispatcher.

### 2. The expectation gets written down

`docs/manual/guides/client-enforcement.md` is the page that already tells
operators how to make the gateway the only door. It gains the other half — how
to notice when something you already hold has been withdrawn — with a runnable
check and the plain statement that `unknown` must be treated as withdrawn.

### 3. The check is not a fetch, and does not land on the ledger

Deliberate, and stated here because the invariant "every fetch and every admin
action lands in the append-only ledger" invites the opposite reading. A status
query is neither. Recording one row per client per poll would multiply the
growth of the one table the architecture assessment already names as "the table
that ends the deployment", to record that a client asked a question rather than
received content. A counter metric gives an operator the same signal — clients
are checking in — at fixed cost.

## Capabilities

### New Capabilities

None. This is the facade's existing PAT-authenticated surface answering one more
question.

### Modified Capabilities

- `git-facade`: gains a client-discoverable revocation check
  (`GW_FACADE_0031`) and the disclosure rule that governs its answers
  (`GW_FACADE_0032`).

## Impact

- **API**: one new endpoint on a new path prefix. Additive within the major, per
  `docs/manual/reference/compatibility.md`.
- **Backend**: a controller and a repository query in the existing
  `facade` package, plus one filter chain in `SecurityConfig` beside
  `publishChain`. No new package.
- **Schema**: none. Every fact the answer needs is already on the `snapshots`
  row, including the revocation time and kind that `administrative-revocation`
  added.
- **Trust boundary.** The check answers questions about the served set to a
  credential-holding caller. `.claude/skills/old-coder` discipline applies:
  adversarial and negative tests for the scoping and disclosure half, not
  happy-path coverage.
- **The stop rule.** `docs/manual/capability-map.md` cannot absorb this: the map
  describes what the gateway does, and the gap is that a capability it already
  claims — revocation — stops at the gateway's own edge. The change adds no
  backend package, no estate object type, no grantable role, no scheduled sweep
  and no configuration leaf; the request bound is a constant, not a knob, so
  `ConfigSurfaceBudgetTests` does not move.
- **Declarative estate (#65) does not extend.** Nothing here is converge-able
  state — the check is a read, and the revocations it reports are acts, the line
  `administrative-revocation` already drew.
- **Docs**: `guides/client-enforcement.md`, the REST API reference, and the
  concepts page that describes the ledger and snapshot lifecycle.
- **Out of scope**: reaching into a client filesystem. The gateway does not
  control client machines and should not pretend to; the goal is that a client
  can *know*.
- **Already closed, contrary to #427**: the issue's "there is no direct
  administrative revoke" is stale — `GW_APPROVAL_0015 — Administrative
  revocation of an approved snapshot` shipped on 2026-09-19. The issue also
  cites `GW_FACADE_0032` for mirror propagation; that requirement is
  `GW_FACADE_0022 — Revocation propagates to the mirror`, and 0032 is free.
