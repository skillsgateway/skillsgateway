# Tasks: admin-revokes-personal-tokens

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_AUTH_0055 and SVC_GW_AUTH_0055 in `docs/reqstool/`.

## 2. Administrative reach (SVC_GW_AUTH_0055)

- [x] 2.1 `PrincipalTokenAdministrationTests`: an admin lists and revokes a
  principal's PAT and session credential, and both stop authenticating; the
  ledger records it; a repeat revokes none; a machine credential under the same
  principal is untouched; a no-role session is refused and revokes nothing.
  Watch it fail first.
- [x] 2.2 `PrincipalTokenController`, `TokenService` and `TokenRepository`
  (`@Requirements GW_AUTH_0055`). Classify both routes in
  `RoleEnforcementTests` and in `MachineApiRegistry`'s unreachable set; the
  route-walk helpers fill `{principal}`. Make 2.1 green.
- [x] 2.3 Regenerate `openapi.json` and `types.gen.ts`.

## 3. Documentation

- [x] 3.1 `reference/api/tokens.md` (the two routes) and `reference/api/audit.md`
  (the event). Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
