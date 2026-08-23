# Tasks: hosted-marketplaces

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0101 (a marketplace may be gateway-hosted: registered with no
      clone URL, given a gateway-owned origin repository, never fetched from
      anywhere, and pinned to on-demand refresh), GW_0102 (authenticated git
      push into that origin over a separate endpoint: push-scoped tokens with
      no wildcard, out-of-scope indistinguishable from not-found, only the
      single lineage ref accepted, deletes always refused, history rewrites
      refused unless the marketplace's policy allows them and ledger-recorded
      when it does, and the consumer facade still accepting no push at all) and
      GW_0103 (a pushed commit traverses quarantine, manifest validation,
      vetting and approval exactly as fetched content, and is served only after
      approval) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0101, SVC_GW_0102 and SVC_GW_0103 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Tests first — each observed failing before its implementation exists

- [x] 2.1 `HostedMarketplaceTests` (extends `AbstractGatewayTest`),
      `@SVCs({"SVC_GW_0101"})`: registration with `origin=hosted` and no url
      succeeds and creates the origin repository; with `origin=hosted` *and* a
      url is refused; with `origin=upstream` and no url is refused as today;
      `PUT /marketplaces/{name}/sync` refuses a hosted marketplace; the
      marketplace view reports the origin, the push policy and the publish
      clone URL
- [x] 2.2 `HostedPushTests`, `@SVCs({"SVC_GW_0102"})`, driving the real git
      binary through `AbstractGatewayTest.git(...)`: a push-scoped token pushes
      `main` and the objects land in the origin repository; a fetch-scoped
      token, a wildcard-fetch token (`scopes IS NULL`) and a legacy token with
      no push scopes are all refused (F2); a push-scoped token pushing to
      another marketplace gets the not-found answer (F3); a push to
      `/git/{name}` is refused because the consumer facade has no receive-pack
      (F1); a traversal name is refused (F11)
- [x] 2.3 Lineage and immutability, same `@SVCs`: pushing a second branch and
      pushing a tag are refused (F5); a ref delete is refused under both
      policies (F6); a force-push is refused under `append-only` and the origin
      tip is unchanged (F4); under `allow-rewrite` it succeeds and writes a
      ledger entry naming the old and new tip
- [x] 2.4 `HostedLifecycleTests`, `@SVCs({"SVC_GW_0103"})`: push → the snapshot
      is `held` with the pushed SHA → the facade 404s the marketplace (F8) →
      approve → a real `git clone` of `/git/{name}` returns the pushed content →
      revoke → the clone fails again. A push of the tainted fixture lands
      `held` with findings; a push whose manifest declares a non-local source
      lands `rejected` (F7)
- [x] 2.5 Null-URL regression sweep, `@SVCs({"SVC_GW_0101"})`: provenance,
      the marketplace listing, the estate reconciler's URL comparison and forge
      metadata resolution all survive a hosted marketplace (F9)
- [~] 2.6 RED observed for `HostedMarketplaceTests` only — all four failed on a
      stubbed `register(...)` throwing, not on compilation. The push, lineage
      and lifecycle tests were written after their implementation; the mutation
      pass in `evidence.md` is the compensating evidence and the gap is recorded
      there.

## 3. Schema and model

- [x] 3.1 `V1__init.sql`: `marketplaces.url` nullable, `marketplaces.origin`,
      `marketplaces.push_policy`, the two CHECK constraints, and
      `access_tokens.push_scopes` (see `design.md` § Migration)
- [x] 3.2 `Marketplace` record gains `origin` and `pushPolicy` with constants;
      `MarketplaceRepository.map` and `register(...)` carry them
- [x] 3.3 `AccessToken` gains `pushScopes` with `permitsPushTo(marketplace)` —
      null means none, deliberately unlike `permitsMarketplace`;
      `TokenService.create` accepts and validates them against registered
      marketplaces; `@Requirements({"GW_0102"})`

## 4. Storage and registration

- [x] 4.1 `GitStorage.hosted(String)` / `hostedIfPresent(String)` and the
      `FilesystemGitStorage` implementation over `{data-dir}/hosted`;
      `@Requirements({"GW_0101"})`
- [x] 4.2 `MarketplaceRegistrationService`: a `hosted` registration skips the
      scheme allowlist, refuses a supplied url, and creates the origin
      repository; an `upstream` registration is unchanged;
      `@Requirements({"GW_0101"})`
- [x] 4.3 `SyncService.changeMode` refuses a hosted marketplace

## 5. The publish endpoint

- [x] 5.1 `GitPublishConfiguration`: `ServletRegistrationBean<GitServlet>` at
      `/publish/*`, resolver enforcing the name pattern then the push scope
      then `origin=hosted` (not-found for every failure), receive-pack and
      upload-pack factories; `GitFacadeConfiguration` is not edited;
      `@Requirements({"GW_0102"})`
- [x] 5.2 `HostedPushHook` implementing `PreReceiveHook` (single lineage ref,
      no deletes, fast-forward unless `allow-rewrite`) and `PostReceiveHook`
      (ledger entry `marketplace-pushed` with the acting token, the old and new
      tip, then hand off to ingestion); `@Requirements({"GW_0102", "GW_0103"})`
- [x] 5.3 `SecurityConfig`: a `publishChain` at `/publish/**` beside `gitChain`
      — PAT only, stateless, CSRF off; `PatAuthenticationProvider` unchanged
      (the push scope lives on the `AccessToken` already set as details)

## 6. Ingestion

- [x] 6.1 `IngestionService`: factor the upstream fetch behind a source
      resolution — `upstream` fetches `marketplace.url()` as today, `hosted`
      fetches the origin repository's `refs/heads/main` by path — leaving
      everything from the snapshot pin down untouched;
      `@Requirements({"GW_0103"})`
- [x] 6.2 `ApprovalService.provenance` and the marketplace/provenance views
      report the origin and tolerate a null upstream url
- [x] 6.3 `EstateReconciler.reconcileMarketplace`: null-safe URL comparison,
      and `DeclaredMarketplace` gains `origin` and `pushPolicy` so a hosted
      marketplace is declarable (`CLAUDE.md` estate obligation)

## 7. Portal

- [x] 7.1 Regenerate `openapi.json` → `types.gen.ts` so the portal's types carry
      `origin`, `pushPolicy`, `publishPath` and `pushScopes`
- [ ] 7.2 ~~Register dialog offers the origin; the marketplace page shows the
      publish URL; a Playwright test covers it~~ — **deliberately deferred.**
      The API is the management surface for this, as it already is for role
      grants ("No portal UI for managing grants yet"), and a hosted marketplace
      is registered once by an operator rather than day to day. The generated
      types are in place so the UI is a self-contained follow-up.

## 8. Documentation (same PR)

- [x] 8.1 New `docs/manual/guides/publishing-first-party-skills.md`: register,
      mint a push-scoped token, push, watch it appear held, approve
- [x] 8.2 `docs/decisions/0007-first-party-hosting-and-the-publish-endpoint.md`
      — why a write path exists, why it is a separate endpoint and a separate
      repository, and what it deliberately does not change (auto-approval stays
      parked per ADR 0006); referenced from `docs/manual/reference/decisions.md`
      and `architecture.md`
- [x] 8.3 Correct every page that states pushes are impossible, without
      weakening what is still true of the *facade*:
      `reference/git-facade.md`, `concepts/trust-boundaries.md`,
      `reference/compatibility.md`, `concepts/glossary.md`,
      `guides/consuming-skills.md`, `concepts/lifecycle.md`
- [x] 8.4 `guides/registering-a-marketplace.md`, `reference/api/marketplaces.md`,
      `reference/api/tokens.md`, `guides/declarative-estate.md`,
      `reference/configuration.md`, `guides/upstream-sync.md`, and the
      lifecycle diagram source in `docs/diagrams/`; `mkdocs.yml` nav

## 9. Gates and evidence (old-coder gauntlet)

- [ ] 9.1 `./mvnw clean verify`
- [ ] 9.2 `(cd src/main/frontend && pnpm test:stories)` and `pnpm e2e`
- [ ] 9.3 `reqstool status local -p docs/reqstool` ends PASS;
      `openspec validate --all --strict`; `mkdocs build --strict`
- [ ] 9.4 Mutation pass over the receive hook, the publish resolver and the
      push-scope check, with a negative control; adversarial pass;
      `evidence.md` with one fresh final run of every gate and the commit SHA
