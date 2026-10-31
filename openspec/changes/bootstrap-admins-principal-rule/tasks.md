# Tasks: bootstrap-admins-principal-rule

## 1. Requirements (reqstool)

- [ ] 1.1 Widen `GW_AUTH_0060` to the configured administrator list and add
  `SVC_GW_AUTH_0085` in `docs/reqstool/`; `reqstool status local -p
  docs/reqstool` lists the new SVC.

## 2. Startup refusal (SVC_GW_AUTH_0085)

- [ ] 2.1 `RoleBootstrapGuardTests` (`@SVCs SVC_GW_AUTH_0085`): leading space,
  trailing space, tab, zero-width, bidi control, blank, 256 code points, and
  one bad entry among good ones (index named, value not echoed) each refuse
  startup; well-formed entries including the 255 boundary start. Watch each
  refusal fail before the fix.
- [ ] 2.2 `RolePrincipals.grantProblem` shared by `requireGrantable` and
  `RoleBootstrapGuard` (`@Requirements GW_AUTH_0060`); 2.1 and
  `RolePrincipalsTests` pass.

## 3. Documentation

- [ ] 3.1 `docs/manual/reference/configuration.md` describes the rule for
  `skills-gateway.roles.admins`; `mkdocs build --strict` passes.

## 4. Gates and evidence

- [ ] 4.1 `mutants.sh` kills every mutant; a dry run proves each applies.
- [ ] 4.2 Every AGENTS.md gate fresh after the last code edit, recorded in
  `evidence.md` with the commit SHA.
