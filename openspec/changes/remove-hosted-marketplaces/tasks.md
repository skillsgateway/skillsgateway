# Tasks: remove-hosted-marketplaces

The guard touches data loss, so the `.claude/skills/old-coder` discipline applies
to sections 3-5: tests first, each watched failing for the right reason. The
executable spec is `design.md` (decisions 1, 2, 4, 5, 6) and is stated in the PR;
`evidence.md` records `spec approval: not obtained (autonomous run)`.

## 1. Requirements (reqstool SSOT, before code)

- [x] 1.1 Add `GW_AUTH_0090 — The removed hosting surface is refused, never silently reinterpreted` and `SVC_GW_AUTH_0110`, `SVC_GW_AUTH_0111` to `docs/reqstool/` (ids from the owned range).
- [x] 1.2 Revise (clause removed, `revision` bumped) `GW_FACADE_0008`, `GW_FACADE_0011`, `GW_RETENTION_0010`, `GW_INGEST_0035`, `GW_INGEST_0066`, `GW_APPROVAL_0044.2`, `GW_INGEST_0070`, `GW_AUTH_0080` and the SVC texts that name hosting (`SVC_GW_FACADE_0036`, `SVC_GW_AUTH_0018`, `SVC_GW_FACADE_0011`, `SVC_GW_INGEST_0035`, `SVC_GW_INGEST_0063`, `SVC_GW_INGEST_0066`, `SVC_GW_APPROVAL_0044.2`, `SVC_GW_AUTH_0098`).
- [x] 1.3 Remove `GW_FACADE_0006`, `GW_FACADE_0007`, `GW_INGEST_0017` and `SVC_GW_FACADE_0006`, `SVC_GW_FACADE_0007`, `SVC_GW_FACADE_0035`, `SVC_GW_INGEST_0017`; fix every `references:` that cites a removed id (`GW_INGEST_0034`'s neighbour naming rule cites `GW_FACADE_0006`).

## 2. ADR

- [x] 2.1 Add `docs/decisions/0024-…` (Status Proposed) superseding ADR 0007; mark ADR 0007 superseded as other superseded ADRs are marked; update the ADR index if one exists.

## 3. RED: tests that fail before the implementation

- [x] 3.1 `HostedRemovalMigrationTests` (SVC_GW_AUTH_0110), real PostgreSQL schemas staged at V1 and at V2, rows the previous schema allowed: (a) no hosted marketplace: V3 applies, every row and surviving column unchanged, `url` NOT NULL restored, tokens fetch; (b) a live and a removed hosted marketplace carrying snapshots, closures, facts, vetting runs/verdicts/findings, overrides, reversals, waivers, exemptions, grants, toggles, chain settings, ledger entries by id, old null-id ledger entries and a sighting, beside an unrelated upstream and a later upstream that reused a purged name: V3 succeeds, exact row counts per table, no row of any table referencing a purged id, the unrelated and name-reuse history byte-for-byte intact, the reused name's null-id entries left, and the hosted repository folder byte-for-byte untouched; (c) a fresh database and an upgraded one have the same schema.
- [x] 3.2 `RemovedHostingRefusalTests` (SVC_GW_AUTH_0111): `POST /api/v1/marketplaces` with `origin: hosted` (with and without `url`) and with `pushPolicy` is `400` naming the removal; `origin: upstream` still registers; `POST /api/v1/tokens` with non-empty `pushScopes` is `400` and mints nothing; an estate entry declaring `origin: hosted` or `push-policy` fails its entry naming the removal; `/publish/x` (with and without credentials) answers as `/nothing-here/x` does; the responses carry no `origin`, `pushPolicy`, `publishPath` or `pushScopes`.
- [x] 3.3 `FormerHostedRepositoriesTests` (SVC_GW_AUTH_0112): an administrator sees a hosted repository folder with name, location, last commit date and short sha; a non-administrator session and a machine credential are refused; the list is empty when there is nothing; a symlink, a stray file and a mis-named directory are ignored; the list is empty after the folder is deleted; nothing in the folder changed.
- [x] 3.4 Portal tests (SVC_GW_AUTH_0113): the banner shows for an administrator with the count and each repository, shows nothing for an empty list, and a non-administrator causes no request.
- [x] 3.5 Watch each fail for the stated reason; paste tails into `evidence.md`.

## 4. Database

- [x] 4.1 `V3__remove_hosted_marketplaces.sql` per `design.md` decision 1 (guard, purge, `url NOT NULL`, drops); never touch `V1`/`V2`.
- [x] 4.2 Confirm 3.1 passes; confirm `SchemaShapeTests`, `NativeEnumColumnTests` and the other schema tests pass or are edited for the removed types.

## 5. Backend removal

- [x] 5.0 `FormerHostedRepositories` (temporary, javadoc names the removal condition), its route, `MachineApiRegistry` unreachable entry, `FilesystemGitStorage`/`ObjectStoreGitStorage` listing hooks.
- [x] 5.1 Delete `HostedPushHook`, `GitPublishConfiguration`, `publishChain`; drop `/publish/` from `BrowserNotFoundAdvice`.
- [x] 5.2 `MarketplaceRegistrationService`, `AdminController`, `Marketplace`, `MarketplaceRepository`, `MarketplaceRemovalService` (push-scope revocation and its event), `SyncService`, `IngestionService.fetchIncoming`, `ApprovalService` views.
- [x] 5.3 `TokenService`, `TokenController` (refusal only), `AccessToken`, `TokenRepository` (`removePushScope`), keeping edits to shared token files minimal.
- [x] 5.4 `GitStorage` (`Role.HOSTED`, `hosted()`, `hostedIfPresent()`), `FilesystemGitStorage`, `ObjectStoreGitStorage`, `StorageMigration`.
- [x] 5.5 `InternalSources`, `AutoApprovalSettingService` (`AllowedBy.HOSTED`), `AutoApprovalService`, `NotInternalException`, `EstateReconciler`, `SkillsGatewayProperties` javadoc.
- [x] 5.6 Delete `HostedPushTests`, `PushScopeMintingTests`, `HostedMarketplaceTests`, `HostedLifecycleTests`, `ClaimMappedPushScopeTests`; edit each partly hosted test class, changing fixtures and not assertions about other behaviour; re-measure and lower `ContextBudgetTests` if it drops.
- [ ] 5.7 `./mvnw -q spotless:apply`; run the full Java suite.

## 6. Contract

- [ ] 6.1 `cp target/openapi.json src/main/frontend/openapi.json && (cd src/main/frontend && pnpm run gen:api-types)`; run `OpenApiContractTests`; run oasdiff locally against the base contract and record that it classifies breaking.

## 7. Portal

- [x] 7.0 The Overview administrator banner for the former hosted repositories (role-gated query, no delete control), with stories and tests.
- [x] 7.1 Remove the "publishes to" line (`tokens.tsx`, test, stories), the hosted branch (`marketplace-detail.tsx`), the hosted label (`auto-approval.tsx`), and the fixtures (`msw-handlers.ts`).
- [x] 7.2 `/impeccable audit` on `tokens` and `marketplace-detail`; fix or record dismissals.
- [ ] 7.3 Before/after screenshots (ui-screenshots recipe A, throwaway stories copied into both trees and deleted afterwards, fixture data only) kept in a git-ignored folder under `.claude/worktrees/`; nothing uploaded, no label applied.

## 8. Documentation (same PR)

- [x] 8.1 Delete `guides/publishing-first-party-skills.md`; add `guides/removing-hosted-marketplaces.md` (the after-the-upgrade procedure with an optional before-the-upgrade step, the outage, the deleted history, push-only tokens, the leftover folders and the notice); update `mkdocs.yml`.
- [x] 8.2 Update `reference/api/{tokens,marketplaces,audit}.md`, `concepts/{trust-boundaries,glossary,lifecycle}.md`, `reference/{configuration,portal,git-facade,compatibility}.md`, `guides/registering-a-marketplace.md`, `capability-map.md`, and every remaining mention found by `grep -rniE "hosted|push scope|/publish"` under `docs/manual`, `README.md`, `ARCHITECTURE.md`, `helm/`.
- [x] 8.3 Link ADR 0024 by its full name where a page explains the stance; `mkdocs build --strict`.

## 9. Gates and evidence

- [ ] 9.1 One final fresh run after the last edit, under the shared gate lock: `./mvnw clean verify`; `pnpm test:stories`; `pnpm e2e` with `E2E_GATEWAY_PORT=18083`; `reqstool status local -p docs/reqstool` (PASS); `openspec validate --all --strict`; `mkdocs build --strict`.
- [ ] 9.2 Write `evidence.md` (commands, result tails, commit SHA).
- [ ] 9.3 Grep commit messages, PR body and added lines for the confidentiality terms.

## Workflow follow-up

- `/opsx:archive` as the final commit of the PR; open the draft PR on `feat/portal-pat-scopes`.
