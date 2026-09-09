# Tasks: derive-privileged-reads

Finding F5 of `docs/analysis/2026-09-08-architecture-assessment.md`, whose live
instance was F6. Verification-only: no production code, no new requirement.

## 1. Requirements (SSOT first)

- [x] 1.1 **No requirement added.** GW_AUTH_0010 — Scoped admin roles with
      deny-by-default enforcement already states the guarantee this extends the
      verification of; nothing the gateway does changes
- [x] 1.2 SVC_GW_AUTH_0010 amended — both sets derived, every read classified;
      revision "0.1.0" → "0.2.0"

## 2. Derive the reads

- [x] 2.1 Parameterise the route-table walk by HTTP method rather than skipping
      `GET`
- [x] 2.2 `readRoutesFromTheRouteTable()`, asserted equal to the union of the
      four read sets
- [x] 2.3 Four named sets, each with a javadoc saying what standing it takes

## 3. Walk them

- [x] 3.1 Privileged reads: refused to a no-role session (unchanged)
- [x] 3.2 Marketplace-scoped reads: refused, against a **real** snapshot so the
      refusal is the guard and not a 404
- [x] 3.3 Open and owner-scoped reads: 200, replacing ten hand-written calls
- [x] 3.4 Auditor refused the marketplace-scoped reads — the decision taken for
      the blast-radius report, pinned rather than assumed
- [x] 3.5 `/api/snapshots/{id}/file` needs its `path` parameter supplied: argument
      binding runs before the handler, so without one the walk meets a 400 raised
      before any authorization check ever runs

## 4. Prove the control discriminates

- [x] 4.1 Remove one route from `OPEN_READS` and re-run: the suite must fail.
      It does — the assertion names the derived set and the omission. Reverted.
      A completeness assertion that cannot fail is decoration

## 5. Docs

- [x] 5.1 `guides/delegated-administration.md` — "snapshot contents" in the
      browsing surface read as though the preview panes were open. They are not,
      and never were. Corrected while the classification is being made explicit

## 6. Gates

- [x] 6.1 `./mvnw clean verify`
- [x] 6.2 `pnpm test:stories`
- [x] 6.3 `pnpm e2e`
- [x] 6.4 `reqstool status local -p docs/reqstool` ends PASS
- [x] 6.5 `openspec validate --all --strict`
- [x] 6.6 `mkdocs build --strict`
- [x] 6.7 `evidence.md`
