# Tasks: warn-duplicate-upstream-url

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_INGEST_0029 (normalized-URL comparison at registration; a match is a
      non-blocking warning naming the existing marketplace, never a refusal)
      to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_INGEST_0029 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Backend (SVC_GW_INGEST_0029)

- [x] 2.1 `CloneUrlNormalizer` (new, `dev.skillsgateway.server.admin`):
      lowercase scheme and host, strip a trailing slash, strip a `.git`
      suffix, path case preserved; null for a value with no host
- [x] 2.2 `MarketplaceRegistrationService`: `RegistrationOutcome(Marketplace,
      List<String> warnings)` replaces `Marketplace` as both `register(...)`
      overloads' return type; `duplicateUrlWarnings` runs next to
      `requireAllowlistedScheme`, comparing against `marketplaceRepository.list()`.
      Annotated `@Requirements({"GW_INGEST_0029"})`
- [x] 2.3 `AdminController`: `RegisteredMarketplace` record (every
      `Marketplace` field, flat, plus `warnings`) replaces `Marketplace` as
      `registerMarketplace`'s response type
- [x] 2.4 `EstateReconciler` and its test call sites: no change needed — both
      already discard the register call's return value

## 3. Frontend

- [x] 3.1 `form-rules.ts`: fix the same trailing-slash/`.git`-order bug in
      `normalizeCloneUrl` that `CloneUrlNormalizerTests` caught server-side,
      so the two rules stay identical
- [x] 3.2 `queries.ts`: `useRegisterMarketplace` typed against the generated
      `RegisteredMarketplace` schema
- [x] 3.3 `marketplaces.tsx`: `RegisterMarketplaceDialog`'s `onSuccess` shows
      each entry of the response's `warnings` as its own toast, alongside the
      existing pre-submission dialog and acknowledgement (unchanged)

## 4. Tests (never weakening an existing SVC test)

- [x] 4.1 `CloneUrlNormalizerTests` (plain JUnit, `SVC_GW_INGEST_0029`): host case, a
      trailing slash, a `.git` suffix and combinations of the two orders; path
      case preserved; scheme/port distinguish; null/blank/unparseable → null
- [x] 4.2 `DuplicateUrlWarningTests` (`AbstractGatewayTest`, `SVC_GW_INGEST_0029`):
      registering a normalized-duplicate URL returns 201, still creates the
      marketplace, and names the earlier one in `warnings`; a non-colliding
      URL carries no warning; two existing marketplaces sharing a URL are both
      named
- [x] 4.3 `form-rules.test.ts` (vitest): the same normalization table as
      `CloneUrlNormalizerTests`, asserted against the TypeScript
      implementation
- [x] 4.4 `marketplaces.test.tsx`: a registration response carrying
      `warnings` shows a toast for each entry, independent of what the
      pre-submission dialog did or didn't catch

## 5. Docs (same PR)

- [x] 5.1 `guides/registering-a-marketplace.md`: "Duplicate upstream URLs"
      section — the normalization rule, the warning, why it never blocks
- [x] 5.2 `reference/api/marketplaces.md`: `warnings` documented on
      `POST /marketplaces`
- [x] 5.3 `reference/portal.md`: the post-submission toast alongside the
      existing pre-submission dialog description

## 6. Gates and archive

- [x] 6.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [x] 6.2 Regenerate `src/main/frontend/openapi.json` and `types.gen.ts`
- [x] 6.3 `openspec/changes/warn-duplicate-upstream-url/evidence.md`
- [x] 6.4 Archive the change as the final commit of the PR
