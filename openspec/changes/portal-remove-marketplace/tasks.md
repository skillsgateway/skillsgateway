# Tasks: portal-remove-marketplace

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0048 and GW_INGEST_0049, each with its SVC
  (SVC_GW_INGEST_0048, SVC_GW_INGEST_0049), to `docs/reqstool/`. These are
  the only ids reserved for this change. Verify that `reqstool status` lists
  them.

## 2. Portal (SVC_GW_INGEST_0048, SVC_GW_INGEST_0049)

- [x] 2.1 `queries.ts`: `useRemoveMarketplace` (a `DELETE` with a reason body)
  and `useEstateReport` (reads `GET /api/v1/estate`, admin only). Verify with
  `pnpm verify`.
- [x] 2.2 `components/remove-marketplace.tsx`: the card and the confirmation
  dialog, annotated `@Requirements GW_INGEST_0048, GW_INGEST_0049`. The dialog
  names the marketplace, states the consequences, has a required reason, shows
  "Removing…" while the request is in flight, and on success navigates to
  `/marketplaces` with a toast. The control is disabled, with the reason on
  screen, for a declared marketplace. Render it on Settings for administrators
  only. Verify with component tests in `marketplace-detail.test.tsx`: the card
  is hidden for a non-admin; confirm is disabled when the reason is empty or
  whitespace and enabled once it holds text; the request carries the trimmed
  reason, then the page lands on `/marketplaces`; a refusal keeps the dialog
  open; a declared marketplace's control is disabled with its explanation.
  Tag the tests `@SVCs`.
- [x] 2.3 A story for the card, covering the enabled and declared states, with
  axe. Verify with `pnpm test:stories`.
- [x] 2.4 An e2e test in `e2e/portal.spec.ts`, tagged `SVC_GW_INGEST_0048`:
  alice registers, ingests and approves a marketplace, then removes it from
  Settings. The test checks that confirm is disabled until a reason is written,
  that the portal lands on the marketplace list, and that the name is gone from
  it. Verify with `pnpm e2e`.
- [x] 2.5 Run `/impeccable audit` and `/impeccable harden` on the Settings
  section and the dialog. Fix each finding, or dismiss it in the PR body.

## 3. Documentation

- [x] 3.1 Update `reference/portal.md` (Settings), the "Removing a marketplace"
  section of `guides/registering-a-marketplace.md` (portal and API tabs), and
  the estate guide's statement that removal is API-only. Verify with
  `mkdocs build --strict`.

## 4. Gates and evidence

- [ ] 4.1 Run all six gates fresh after the last code edit. Record the
  commands, the result tails and the SHA in `evidence.md`.
