# Evidence: bound-waiver-lifetime

Final fresh run of all gates after the last code edit, at commit `2bf035f0`.

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | `Tests run: 994, Failures: 0, Errors: 0, Skipped: 9` / `BUILD SUCCESS` |
| `pnpm test:stories` | `Test Files 17 passed (17)`, `Tests 88 passed (88)` (first run hit the known dynamic-import flake; cache cleared, re-run green) |
| `pnpm e2e` | `24 passed (1.2m)` |
| `reqstool status local -p docs/reqstool` | `324/324 complete · 0 incomplete · PASS` |
| `openspec validate --all --strict` | `Totals: 31 passed, 0 failed (31 items)` |
| `mkdocs build --strict` | `Documentation built in 1.48 seconds` |

New coverage: `WaiverTests.aWaiverPastNinetyDaysIsRefusedAndOneAtTheCapIsAccepted`
(`SVC_GW_VETTING_0007.2`) and the portal test
`the_waiver_expiry_is_capped_at_ninety_days`. Three existing tests that armed a
waiver with a far-future fixture expiry (`RoleEnforcementTests`,
`ClaimRoleMappingTests`) now use 30 days; their assertions are unchanged.
