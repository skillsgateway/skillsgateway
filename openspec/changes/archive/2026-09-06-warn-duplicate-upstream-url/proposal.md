# Proposal: warn-duplicate-upstream-url

## Why

Registering `https://github.com/reqstool/reqstool-ai` under two different
marketplace names — `reqstool-ai` and `ri-2` — succeeds silently today: no
warning from the API, none in the portal. Tracking one upstream under two
names is legitimate (it is how a marketplace is tested before promotion), so
the gateway must not refuse it, but the far more common case is a mistake — a
typo'd name, or forgetting an earlier registration already exists — and today
nothing anywhere says so.

PR [#227](https://github.com/skillsgateway/skillsgateway/pull/227) already
shipped a client-side pre-submission check in the portal's register dialog
(`normalizeCloneUrl` in `form-rules.ts`): it compares the URL being typed
against the marketplaces already loaded on the page and gates **Register**
behind a "register anyway" acknowledgement. That check has a hole: it only
ever sees the marketplace list this page happened to have loaded, so it
misses a marketplace registered by someone else between page load and this
submission, and it does nothing at all for a caller that never goes through
the portal — the estate reconciler, or a script calling the API directly.

Issue [#239](https://github.com/skillsgateway/skillsgateway/issues/239).

## What Changes

- **The server checks too, authoritatively**, in
  `MarketplaceRegistrationService.register`, next to the existing URL-scheme
  allowlist check: every other upstream marketplace's clone URL is compared
  against the one being registered, both normalized the same way — GW_0166.
- **Normalization** (`CloneUrlNormalizer`, new): lowercase scheme and host, no
  trailing slash, no `.git` suffix. The path keeps its case — most forges
  treat it as case-sensitive. Mirrors the portal's existing
  `normalizeCloneUrl` exactly (a latent ordering bug in both — a trailing
  slash *after* a `.git` suffix, `…/repo.git/`, survived the `.git` strip — is
  fixed in both as part of this change).
- **Warn, never block.** A match does not fail the registration; the 201
  response carries a new `warnings` array (`RegisteredMarketplace`, replacing
  the raw `Marketplace` as the registration endpoint's response type — every
  existing field is unchanged, `warnings` is additive), one entry per
  colliding marketplace: `"url already registered as <name>"`.
- **The portal surfaces the server's answer too**, not only its own
  pre-submission guess: on a successful registration, each entry in the
  response's `warnings` is shown as its own toast. The existing pre-submission
  dialog and acknowledgement checkbox are unchanged — this closes the gap
  where they miss a collision, it does not replace them.
- Requirement GW_0166 with SVC_GW_0166.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `marketplace-ingestion`: registering an upstream marketplace now compares
  its clone URL, normalized, against every other registered upstream
  marketplace and reports a match as a non-blocking warning in the response.

## Impact

- **DB**: none.
- **Backend**: `CloneUrlNormalizer` (new), `MarketplaceRegistrationService`
  (`RegistrationOutcome` return type, `duplicateUrlWarnings`),
  `AdminController` (`RegisteredMarketplace` response record replacing the raw
  `Marketplace`).
- **API**: additive. `POST /marketplaces`'s 201 body gains `warnings`; every
  other field is unchanged. `src/main/frontend/openapi.json` regenerated.
- **Trust boundary**: none crossed. The check reads the marketplace list that
  is already public via `GET /marketplaces`; it adds no new access and refuses
  nothing.
- **Frontend**: `form-rules.ts` (normalization order fix, shared with the
  server rule), `queries.ts` (`RegisteredMarketplace` type),
  `marketplaces.tsx` (surfaces `warnings` from the response as toasts).
- **Docs** (same PR): `guides/registering-a-marketplace.md`,
  `reference/api/marketplaces.md`, `reference/portal.md`.
