# Tasks: bound-waiver-lifetime

## 1. Requirements (reqstool)

- [x] 1.1 Amend `GW_VETTING_0007` in `docs/reqstool/requirements.yml` (bound on the expiry; revision 0.2.0); add `SVC_GW_VETTING_0007.2`.

## 2. Backend

- [x] 2.1 `WaiverTests` (`@SVCs SVC_GW_VETTING_0007.2`): past the cap refused with no waiver written; exactly at the cap accepted. Watch it fail.
- [x] 2.2 `WaiverService.MAX_LIFETIME` and the check in `create`. Make 2.1 green.

## 3. Portal

- [x] 3.1 vitest for the `max` and the disabled action past it; watch it fail, then implement in `vetting-report.tsx`.
- [x] 3.2 Before/after screenshots saved locally.

## 4. Docs

- [x] 4.1 `waiving-findings.md`, `concepts/vetting.md`, `reference/api/marketplaces.md`.

## 5. Gates and close

- [ ] 5.1 All gates, `evidence.md`, archive.
