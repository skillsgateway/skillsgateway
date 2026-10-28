# Tasks: facade-smart-http-only

## 1. Requirements (reqstool)

- [x] 1.1 Add SVC_GW_FACADE_0036 under GW_FACADE_0002 in `docs/reqstool/`.
  Verify with `openspec validate facade-smart-http-only --strict`.

## 2. Smart HTTP only (SVC_GW_FACADE_0036)

- [x] 2.1 `SmartHttpOnlyTests` (shared context, `@SVCs SVC_GW_FACADE_0036`).
  With a valid credential, the dumb routes are refused on both endpoints:
  `info/refs` without `service=`, `HEAD`, `objects/info/packs`, and the
  approved commit's loose-object path. The smart advertisement on the same
  repository still answers `200`. Watch it fail first.
- [x] 2.2 `servlet.setAsIsFileService(AsIsFileService.DISABLED)` in
  `GitFacadeConfiguration.gitServlet` (`@Requirements GW_FACADE_0002`) and
  `GitPublishConfiguration.publishServlet`. Make 2.1 green, with `FacadeTests`
  and `HostedPushTests` unchanged and green.

## 3. Documentation

- [x] 3.1 `concepts/trust-boundaries.md` says the facade and the publish
  endpoint speak smart HTTP only. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 Manual mutation (`mutants.sh`): drop each setter in turn; 2.1 must
  fail for each.
- [x] 4.2 Run every AGENTS.md gate fresh after the last code edit, and record
  the results and the commit SHA in `evidence.md`.
