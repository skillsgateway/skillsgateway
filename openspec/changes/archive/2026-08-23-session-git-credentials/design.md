# Design: session-git-credentials

## Context

`TokenService.create(principal, name, scopes, expiresAt, pushScopes)` is the one
issuing path. `expiresAt` is the caller's, bounded only by
`skills-gateway.tokens.max-ttl` when an operator sets one — and refused rather
than clamped when it exceeds the cap (GW_0065). That is the right shape for a
personal access token: the holder asks for a lifetime and the policy either
allows it or says no.

It is the wrong shape for the credential ADR 0008 wants. The point of an
SSO-derived credential is that its lifetime is *not* negotiable: it exists
because a login just happened, and it stops existing shortly after. A caller
who can ask for a year has minted a personal access token through a different
URL.

## Goals / Non-Goals

**Goals**

- A git credential a human can get from the session they already have, whose
  lifetime the gateway sets and the caller cannot influence.
- Distinguishable from a standing token everywhere it appears: the listing, the
  ledger, and therefore the fetch attribution that hangs off the token id.
- No publication authority, ever.

**Non-Goals**

- **Not tied to session lifetime.** Revoking on logout would mean tracking
  session end, which the gateway does not do (it is its own BFF, but the
  session is a cookie, not a registry). The TTL is the control and the guide
  says so.
- **Not a replacement for PATs.** CI has no browser.
- **No credential helper.** A client-side convenience over the same endpoint.
- **No forge mirror.** ADR 0008's other half.

## Decisions

1. **A separate endpoint, not a flag on `POST /api/tokens`.** `POST
   /api/tokens/session` takes no `expiresAt` field at all, rather than taking
   one and ignoring it. A field that is silently ignored is a field somebody
   will believe; a field that does not exist cannot be misread. It is also what
   makes the OpenAPI document say the true thing.

2. **The TTL is its own property, not `max-ttl`.**
   `skills-gateway.tokens.session-ttl` defaults to 8 hours — about a working
   day, so the credential lasts as long as the work does and no longer.
   `max-ttl` is a ceiling on what a holder may *ask* for; this is what the
   gateway *grants*. Deriving one from the other would couple two decisions
   that have different reasons: a deployment might allow year-long CI tokens
   and still want session credentials to die at lunchtime.

3. **`session_derived` is a column, not an inference from the TTL.** Inferring
   it ("short expiry, therefore session-derived") would be wrong the moment
   somebody mints a short-lived PAT deliberately, which is a perfectly ordinary
   thing to do. The distinction the ledger needs is *how the credential was
   obtained*, and that is a fact to record, not to reconstruct.

4. **Rotation is allowed and cannot extend.** `rotate` copies the expiry
   deadline (GW_0066), so rotating a session credential re-secrets it without
   moving its death. The `session_derived` mark is copied too, so a rotation
   cannot launder a session credential into a standing one.

5. **Push scopes are refused, not ignored.** Same reasoning as (1): a session
   credential that silently dropped a requested push scope would look like it
   had one. The endpoint has no push-scope field, and the service path passes
   none.

## Failure model (Tier 3)

| # | Failure mode | Layer that catches it |
| --- | --- | --- |
| F1 | A caller extends the lifetime by supplying `expiresAt` | The endpoint has no such field; a test posts one anyway and asserts the granted expiry is still the configured TTL |
| F2 | A session credential can publish | The service path passes no push scopes; a test asserts a real `git push` with one is refused |
| F3 | It is indistinguishable from a standing PAT on the ledger | `session_derived` column, reported on the listing; test asserts a standing token is not marked and a session one is |
| F4 | Rotation launders it into a standing or longer-lived token | `rotate` copies both the deadline and the mark; test asserts both survive rotation |
| F5 | It outlives its TTL because expiry is swept rather than compared | Unchanged from GW_0065 — expiry is a comparison at authentication time; test mints one with a near-zero configured TTL and asserts it fails authentication |
| F6 | It works without a session | The endpoint is on the web chain, which is OIDC-only; the existing 401 behaviour covers it |
| F7 | Scope narrowing is lost, so a session credential silently grants every marketplace | Same `validateScopes` path as any token; test asserts a narrowed session credential is refused another marketplace |

## Risks

- **A short-lived credential still leaks like any bearer token** for the length
  of its life. What it buys is a bounded window and an attributable origin, not
  immunity. The guide says that plainly.
- **Eight hours is a guess about a working day.** It is a property precisely so
  a deployment that disagrees can say so.
