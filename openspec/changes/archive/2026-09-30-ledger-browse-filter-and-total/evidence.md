# Evidence: ledger-browse-filter-and-total

- **Source state:** every gate below ran over `e8ca374b`, the commit after the
  last code edit. The only later changes are this file, `tasks.md` and the
  archive.
- **Tier:** ordinary. The change reads the ledger; it touches no trust boundary
  (facade auth, `ApprovalService`, registration allowlist), and the browse read
  keeps its existing auditor-role check.

## Gates

```console
$ ./mvnw clean verify
[INFO] Tests run: 996, Failures: 0, Errors: 0, Skipped: 9
[INFO]  Test Files  28 passed (28)
[INFO]       Tests  213 passed (213)
[INFO] BUILD SUCCESS
[INFO] Total time:  07:46 min

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  18 passed (18)
      Tests  92 passed (92)

$ (cd src/main/frontend && pnpm e2e)
  24 passed (1.2m)

$ reqstool status local -p docs/reqstool
324/324 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.51 seconds
```

The first `pnpm test:stories` run failed ten files at import
("Failed to fetch dynamically imported module", the known Storybook harness
flake). It passed after deleting `node_modules/.cache/storybook` and
`node_modules/.vite`, with no source change in between.

## What the new tests pin

- `AuditBrowseTests` (SVC_GW_AUDIT_0008): a page narrowed to one marketplace
  holds only its entries, newest first, and its cursor continues within the
  narrowing to a short last page with no cursor; the total on a one-entry page
  is exact and counts the whole ledger; the estimate is used only from 100,000
  up, and the exact count is never read there.
- `audit.test.tsx`, `marketplace-detail.test.tsx`, `overview.test.tsx`
  (SVC_GW_AUDIT_0008): Load older passes `nextBefore` back and stops at the
  oldest entry; Activity asks the server with `marketplace` and renders newest
  first; the overview reads `total` from a one-entry page as "N ledger entries"
  or "about N ledger entries".
