# Tasks: marketplace-name-rule

## 1. Requirements

- [ ] 1.1 Add GW_INGEST_0063 and GW_INGEST_0064 with SVC_GW_INGEST_0063 and SVC_GW_INGEST_0064 to `docs/reqstool/`, and verify `reqstool status` lists them

## 2. Backend rule

- [ ] 2.1 Write the negative tests first (SVC_GW_INGEST_0063, SVC_GW_INGEST_0064): API upstream and hosted — 64 characters refused with 422 naming `/git/<name>`, nothing registered; 63 accepted; estate — a 64-character declaration fails in isolation with the same reason; facade — `/git/` and `/publish/` of a 64-character name answer not-found, the status check answers 400; schema — a direct insert of 64 characters is refused. Verify the API, estate and schema tests fail before 2.2
- [ ] 2.2 Add `persistence.MarketplaceName` (`@Requirements GW_INGEST_0063, GW_INGEST_0064`); point registration, both facade configurations, `HeldContentController` and the `@Schema` on `RegisterMarketplaceRequest` at it; bound the `CHECK` in `V1__init.sql`. Verify the 2.1 tests pass
- [ ] 2.3 Regenerate `openapi.json` and `types.gen.ts`, and verify `OpenApiContractTests` passes

## 3. Portal

- [ ] 3.1 `form-rules.ts`: `MARKETPLACE_NAME`, `MARKETPLACE_NAME_ERROR`, `MARKETPLACE_NAME_HINT`; the register form uses them. Test (SVC_GW_INGEST_0063, SVC_GW_INGEST_0064): 64 characters keeps Register disabled and shows the reason, 63 enables it. Verify with `pnpm test`

## 4. Documentation

- [ ] 4.1 State the 63-character limit where the rule is stated (registering guide, compatibility, git facade, portal, API marketplaces and index, configuration estate table, snapshots-and-ledger). Verify with `mkdocs build --strict`

## 5. Gates and evidence

- [ ] 5.1 Run all gates from CLAUDE.md fresh after the last code edit and record them with the SHA in `evidence.md`
- [ ] 5.2 Archive the change as the PR's final commit, and verify with `openspec validate --all --strict`
