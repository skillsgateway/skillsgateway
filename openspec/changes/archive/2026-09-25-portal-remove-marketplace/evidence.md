# Evidence: portal-remove-marketplace

One fresh run of every gate after the last code edit, at `87193aba`. The
evidence and archive commits that follow change only this file and OpenSpec
bookkeeping.

## Gates

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | BUILD SUCCESS. Java: 848 tests, 0 failures, 9 skipped. UI unit: 22 files, 183 tests (7:34) |
| `pnpm test:stories` | 15/15 files, 74/74 tests, on the 3rd run. Runs 1 and 2 hit the known harness flake after `mvnw clean` (`Failed to fetch dynamically imported module`, and one iframe not ready). No assertion failed in either run |
| `pnpm e2e` | 23 passed, including `admin_removes_a_marketplace_from_its_settings` |
| `uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool` | 298/298 complete · 0 incomplete · PASS |
| `openspec validate --all --strict` | 31 passed, 0 failed |
| `mkdocs build --strict` | built. The two INFO lines about anchors in `adoption.md` and `tokens.md` were already on `main` |

Tails:

```text
[INFO] Tests run: 848, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
[INFO] Total time:  07:34 min

 Test Files  15 passed (15)
      Tests  74 passed (74)

  ✓   2 [chromium] › e2e/portal.spec.ts:152:1 › admin_removes_a_marketplace_from_its_settings (5.8s)
  23 passed (1.2m)

  GW_INGEST_0048      skills-gateway
  GW_INGEST_0049      skills-gateway
298/298 complete · 0 incomplete · PASS

Totals: 31 passed, 0 failed (31 items)
```

## Tests that fail without the code

Manual mutants, each run against `marketplace-detail.test.tsx` and each
reverted afterwards:

| Mutant | Killed by |
| --- | --- |
| Show the card to every user (`isAdmin` → `true`) | `a_user_who_is_not_an_administrator_is_not_shown_removal` |
| Ignore the estate declaration in `disabled` | `a_declared_marketplace_is_not_offered_for_removal` |
| Send the untrimmed reason | `an_admin_removes_the_marketplace_from_settings_on_a_stated_reason` |

3 mutants, 3 killed.

## Known limits

- The `LongNameAtPhoneWidth` story shows the wrapping label for inspection but
  asserts nothing about geometry. A width assertion written for it passed with
  the fix removed too, so it proved nothing and was dropped.
- No server change was made, so the server-side refusals are covered by the
  existing `MarketplaceRemovalTests` (SVC_GW_INGEST_0034). The portal
  tests use a mocked `404` for the refusal path.
