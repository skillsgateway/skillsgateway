# Proposal: hosted-marketplaces

## Why

Every marketplace the gateway governs today must already exist somewhere else.
Registration takes a clone URL (GW_0001), ingestion fetches an upstream default
branch (GW_0002), and `MarketplaceRegistrationService.requireAllowlistedScheme`
refuses a registration without one. An organisation that writes its *own*
skills therefore has to stand up a forge repository first, purely so the
gateway has something to pull from — and then govern the same content in two
places.

Tracked as issue
[#31](https://github.com/skillsgateway/skillsgateway/issues/31). It is the
complement of [#17](https://github.com/skillsgateway/skillsgateway/issues/17):
the roadmap covers pull-through ingestion, not first-party hosting.

The obstacle is not the pipeline — vetting, approval, publication and the
ledger read the quarantine repository by name and SHA and know nothing about
upstreams. It is that there is no way to *get* content in without an upstream,
and the one git write path a gateway could offer is disabled by construction:
`GitFacadeConfiguration` sets `setReceivePackFactory(null)`, and six
documentation pages state that pushes are impossible.

## What Changes

- **A marketplace gains an origin.** `upstream` (today's behaviour, unchanged)
  or `hosted`. A hosted marketplace has no clone URL and is never fetched from
  anywhere; the gateway owns its source of record.
- **A third repository per hosted marketplace**, `{data-dir}/hosted/{name}.git`.
  It is neither quarantine nor published: publishers push to it, and ingestion
  fetches *from* it exactly as it fetches from an upstream URL. Quarantine keeps
  its existing property that only `IngestionService` ever writes it.
- **A separate publish endpoint, `/publish/**`.** Its own servlet, its own
  `ReceivePackFactory`, its own filter chain. The consumer facade at `/git/**`
  keeps `setReceivePackFactory(null)` verbatim — GW_0006 and GW_0007 are
  untouched, and no push can reach a published repository.
- **Push-scoped tokens.** `access_tokens.push_scopes` names the marketplaces a
  token may push to; unset — which every existing token is — may push nothing.
  There is deliberately no "all marketplaces" push scope, unlike fetch scopes.
- **Single lineage and an immutability policy.** A push may update only
  `refs/heads/main`, may never delete a ref, and under the default
  `append-only` policy may never rewrite history. `allow-rewrite` is available
  per marketplace for publishers who need it, and says so on the ledger.
- **A push ingests through the unchanged pipeline.** It lands `held`, is vetted
  by the same connectors, and is served only after somebody approves it.
  Nothing about the approval gate moves.
- Requirements GW_0101 (hosted origin and its registration), GW_0102 (the
  authenticated push path, its scoping, and the lineage and immutability rules)
  and GW_0103 (a pushed snapshot traverses quarantine, vetting and approval
  like any other) with SVC_GW_0101 / SVC_GW_0102 / SVC_GW_0103.
- **ADR 0007** records the write path as an architecture decision, because
  "receive-pack is disabled by construction" is stated in the trust-boundary
  model, the facade reference, the compatibility matrix and the glossary.

## Explicitly not in this change

Issue #31 also asks for a *"configurable fast-path for trusted internal
publishers"* — auto-approval of first-party content. That is deliberately left
out, not overlooked. [ADR 0006](../../../docs/decisions/0006-embedded-cel-for-policy-rules.md)
already decided the question: auto-approval "contradicts the product's first
principle (nothing is served that a person did not approve) and stays parked
until the delegated-approval question is decided deliberately, together with
the risk-tier machinery". Reopening it inside a plumbing change — while
`four-eyes-approval` is in flight *tightening* the same gate — would decide it
by accident. It needs its own proposal and its own ADR.

## Capabilities

### New Capabilities

_None._ Hosting is a second source for an existing capability, not a new one.

### Modified Capabilities

- `marketplace-ingestion`: a marketplace may be gateway-hosted rather than
  fetched from an upstream, and ingestion resolves its source accordingly.
- `git-facade`: a second, separate smart-HTTP endpoint accepts authenticated
  pushes into a gateway-owned origin repository, with the consumer facade
  unchanged and still read-only.
- `token-lifecycle`: a token may carry push scopes, distinct from its fetch
  scopes and empty by default.

## Impact

- **DB**: `marketplaces.origin`, `marketplaces.push_policy`,
  `marketplaces.url` becomes nullable with a CHECK that an upstream marketplace
  still has one; `access_tokens.push_scopes`. Folded into `V1__init.sql` per
  `CLAUDE.md` — Testcontainers recreate the schema every run.
- **Backend**: `GitPublishConfiguration` (new), `HostedPushHook` (new),
  `GitStorage`/`FilesystemGitStorage` (a `hosted(name)` repository),
  `IngestionService` (source resolution), `MarketplaceRegistrationService`
  (hosted registration), `Marketplace`/`MarketplaceRepository`,
  `AccessToken`/`TokenService`/`PatAuthenticationProvider` (push scopes),
  `SecurityConfig` (a `publishChain`), `SyncService` (a hosted marketplace has
  no upstream to sync), `EstateReconciler` (null-safe URL comparison).
- **API**: `POST /api/marketplaces` accepts `origin: hosted` without a `url`;
  marketplace views report the origin, the push policy and the clone URL of the
  publish endpoint; `POST /api/tokens` accepts `pushScopes`.
- **Portal**: `types.gen.ts` regenerated; the register dialog offers the origin.
- **Docs** (same PR): a new `guides/publishing-first-party-skills.md`, plus the
  six pages that currently state pushes are impossible, the registration guide,
  the API reference, the lifecycle concept and its diagram.
- **Trust boundary**: this adds a write path to a product whose serving surface
  is read-only by construction → ADR 0007, old-coder Tier 3, adversarial and
  negative tests required.
- **Declarative estate obligation**: `estate.marketplaces` gains `origin` and
  `pushPolicy` in the same PR, so a hosted marketplace is declarable. Push
  scopes on tokens stay API-only for the same reason tokens already are.
