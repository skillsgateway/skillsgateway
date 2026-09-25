# Evidence: marketplace-name-rule

- **Source state:** `57c9a815` on `feat/marketplace-name-rule`. Every gate below
  ran over that commit, after the last code edit. The commits after it change
  only `openspec/`.
- **Requirements:** GW_INGEST_0063 — Marketplace names are one bounded facade
  path segment; GW_INGEST_0064 — A refused marketplace name says it is the
  clone path.

## Tests shown failing first

`MarketplaceNameRuleTests` against the unchanged code (`13fa2f94`, proposal
only), before `MarketplaceName` existed:

```console
Tests run: 6, Failures: 5, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.MarketplaceNameRuleTests
  a_64_character_declaration_fails_in_isolation_and_says_why        expected: "failed"
  a_64_character_name_is_refused_through_the_api_and_says_why       Status expected:<422> but was:<201>
  a_name_of_the_wrong_characters_is_refused_with_the_same_reason    JSON path "$.detail" (no reason)
  the_facade_answers_a_64_character_name_as_not_found               Status expected:<400> but was:<200>
  the_marketplace_table_refuses_a_64_character_name_from_any_write_path  (no exception)
```

The sixth, `a_63_character_name_registers_…`, passed before and after: it pins
that the bound is not stricter than 63. The facade's `/git/` and `/publish/`
assertions also pass before the change, because no stored marketplace can
carry a name the facade would newly refuse; the status-check assertion in the
same test is the one that failed.

The portal test
`a_64_character_name_keeps_register_disabled_and_the_form_says_it_is_the_clone_path`
failed against the unchanged `marketplaces.tsx` and `form-rules.ts`
(`Tests 1 failed | 5 passed (6)`), and passes after.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 924, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  23 passed (23)
[INFO]       Tests  186 passed (186)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:25 min

$ (cd src/main/frontend && pnpm test:stories)   # second run; the first hit the known import flake
 Test Files  16 passed (16)
      Tests  79 passed (79)

$ (cd src/main/frontend && pnpm e2e)
  23 passed (1.2m)

$ uvx --from reqstool==0.12.0 reqstool status local -p docs/reqstool
305/305 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.65 seconds
```
