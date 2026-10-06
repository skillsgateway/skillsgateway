# Tasks: edit-marketplace-upstream-url

Registration is a trust boundary, so this change is worked under
`.claude/skills/old-coder` at Tier 3. Every new test is shown failing before
the code it guards exists, or against a throwaway mutant when it passes on
first run.

Failure model (each maps to a test or a mutant in `evidence.md`):

- F1 a new URL skips a registration check (scheme, credential, reachability)
- F2 an edit after the first snapshot, or of a hosted marketplace, goes through
- F3 an ingest fetched from the old URL records its snapshot under the new one
- F4 a refused edit leaves a partial write (URL, registrant, metadata, ledger)
- F5 the previous registrant can approve content from the editor's upstream
- F6 the estate converges a URL the API would refuse

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0066 and GW_INGEST_0067 with SVC_GW_INGEST_0066,
  SVC_GW_INGEST_0066.1 and SVC_GW_INGEST_0067; revise GW_ESTATE_0002 and
  SVC_GW_ESTATE_0002 (revision 0.5.0). Verify with
  `openspec validate edit-marketplace-upstream-url --strict`.

## 2. The edit (SVC_GW_INGEST_0066)

- [x] 2.1 `MarketplaceUrlChangeTests` (`@SVCs SVC_GW_INGEST_0066`): the
  accepted change (new URL, editor as registrant, forge metadata re-resolved,
  duplicate warning naming the other marketplace and not itself, one ledger
  entry with previous and new URL, next ingest fetches the new URL), an
  unchanged URL answered with no ledger entry, and each refusal (disallowed
  scheme 400, credential 400, unreadable 502, non-admin 403, snapshot 409,
  hosted 400, removed 404, unknown 404) leaving URL, registrant and ledger
  unchanged. Watch each fail.
- [x] 2.2 `MarketplaceRepository.changeUrlBeforeFirstSnapshot` (transaction:
  `FOR UPDATE`, snapshot check, update) and
  `MarketplaceRegistrationService.changeUrl` sharing the registration
  validations; `PUT /api/v1/marketplaces/{name}/url` in `AdminController` with
  `@Tag`/`@Operation`/`@ApiResponse`/`@Schema`. `@Requirements GW_INGEST_0066`.
  Make 2.1 green with every existing test unchanged.
- [x] 2.3 Four-eyes (F5): extend 2.1 so the edited marketplace's snapshot is
  refused to the editor and allowed to the original registrant under the
  enforcing mode. Watch fail, then green.

## 3. Ingest race (SVC_GW_INGEST_0066.1)

- [x] 3.1 `MarketplaceUrlChangeRaceTests` (`@SVCs SVC_GW_INGEST_0066.1`):
  drive `SnapshotRepository.create` with a stale fetched URL after a change
  (no row, ingest fails with the stated reason, pin removed), and hold a
  snapshot insert's transaction open while a change waits on the row (change
  refused 409 after the insert commits). Watch fail.
- [x] 3.2 `SnapshotRepository.create` takes the fetched URL and checks it
  under `FOR SHARE`; `IngestionService` passes `marketplace.url()`. Update
  other callers of `create`. Green.

## 4. Estate (SVC_GW_ESTATE_0002)

- [x] 4.1 Extend the `SVC_GW_ESTATE_0002` test: the existing differing-URL
  entry gets a snapshot (still fails, URL kept); a new entry without a
  snapshot is reported updated with the declared URL and a ledger entry.
  Watch the new case fail.
- [x] 4.2 `EstateReconciler.reconcileMarketplace` calls `changeUrl(…,
  REPORT)`; update its comment. Green.

## 5. Portal (SVC_GW_INGEST_0067)

- [x] 5.1 Regenerate `openapi.json` and `types.gen.ts`; confirm the diff is
  additive.
- [x] 5.2 `components/edit-marketplace-url.tsx` (`@Requirements
  GW_INGEST_0067`) in the Settings › Upstream card; `useChangeMarketplaceUrl`
  in `api/queries.ts`; a story per state (editable, refused, has snapshot,
  declared).
- [x] 5.3 Vitest unit tests and a Playwright e2e
  `marketplace_url_is_corrected_from_settings` (`@SVCs SVC_GW_INGEST_0067`).
  Watch fail, then green.
- [x] 5.4 `/impeccable audit` on the settings page; fix findings within
  design-conventions.

## 6. Docs

- [x] 6.1 `docs/manual/`: correcting a URL beside removal (how-to and API
  reference), the estate reconciliation rule, the portal settings page, and
  the webhook-mode note (move the upstream hook yourself).

## 7. Gates, evidence, archive

- [x] 7.1 Mutants (`mutants.sh` in the change): drop the scheme check from
  `changeUrl`, drop the snapshot check under the lock, drop the `FOR SHARE`
  URL comparison, keep the old registrant, make the estate use `changeUrl`
  without the snapshot refusal. Each must be killed.
- [x] 7.2 Real execution: run the jar, register a typo'd URL, correct it via
  the API and the portal, ingest.
- [x] 7.3 One fresh run of all gates after the last code edit; write
  `evidence.md` with commit SHA.
- [x] 7.4 Before/after screenshots captured (upload awaits the owner's approval) and the PR.
- [x] 7.5 `/opsx:archive` as the final commit.
