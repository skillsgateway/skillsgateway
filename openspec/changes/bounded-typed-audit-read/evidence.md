# Evidence: bounded-typed-audit-read

One fresh run of every gate after the last edit. Commit `08299614`.

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | `Tests run: 701, Failures: 0, Errors: 0, Skipped: 9`; `Tests 104 passed (104)`; BUILD SUCCESS |
| `pnpm test:stories` | `Tests 45 passed (45)` |
| `E2E_GATEWAY_PORT=18500 pnpm e2e` | `20 passed (1.1m)` |
| `reqstool status local -p docs/reqstool` | `249/249 complete · 0 incomplete · PASS` |
| `openspec validate --all --strict` | `31 passed, 0 failed` |
| `mkdocs build --strict` | built clean |

`AuditBrowseTests`: `Tests run: 4, Failures: 0`.

## One test caught a real consumer

`AdminAuditTests` read `$[?(@.event == …)]` against the old bare array and failed
on the first full run — it was reading the ledger through the endpoint rather than
through the repository, so the envelope change reached it. Updated to
`$.entries[…]` and to ask for a page large enough to hold its own entries rather
than relying on the default, which is the honest fix: the test's subject is what the
ledger recorded, not how much of it one page returns.

That is also the evidence that the change is genuinely breaking for a consumer, not
only on paper.

## What the cursor behaviour is pinned to

The four cases assert the parts a reasonable implementation gets subtly wrong:

- ids descend within a page, and `nextBefore` is the page's own oldest id
- the second page is **strictly** older with **no** id repeated — the property an
  offset page over an append-only table cannot promise
- a short page omits the cursor rather than returning one that points at nothing
- `actor_type`, `token_id` and `credential_kind` are asserted **absent**, because
  their presence as JSON keys was the defect

## Notes

- **Stacked on PR D**, which is stacked on PR C. Reviewing against `main` shows all
  three.
- reqstool 249/249: `GW_AUDIT_0008` is new, on top of PR D's `GW_API_0007`.
- e2e on port 18500, for the reason recorded in PR A's evidence.
