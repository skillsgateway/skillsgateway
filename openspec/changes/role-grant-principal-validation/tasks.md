# Tasks: role-grant-principal-validation

## 1. Requirements (reqstool)

- [ ] 1.1 Add GW_AUTH_0060, GW_AUTH_0061 and SVC_GW_AUTH_0063 … SVC_GW_AUTH_0067
  in `docs/reqstool/`; `reqstool status local -p docs/reqstool` lists them.

## 2. Principal rule and the grants API (SVC_GW_AUTH_0063, SVC_GW_AUTH_0065)

- [ ] 2.1 `RolePrincipalsTests` (SVC_GW_AUTH_0065): named cases plus a seeded
  randomized sweep against an independent oracle. Watch it fail against a stub.
- [ ] 2.2 `RoleGrantPrincipalTests` (SVC_GW_AUTH_0063): the API refuses each
  malformed principal with 422, stores and ledgers nothing, and does not echo
  it; well-formed principals including the 255 boundary are granted. Watch the
  refusals fail first.
- [ ] 2.3 `RolePrincipals` and its use in `RoleService.grant`
  (`@Requirements GW_AUTH_0060`); 2.1 and 2.2 pass.
- [ ] 2.4 `RoleController` API description of `principal` and the 422 response;
  regenerate `openapi.json` and `types.gen.ts`; `OpenApiContractTests` passes.

## 3. Estate grants (SVC_GW_AUTH_0064)

- [ ] 3.1 `EstateReconciliationTests`: a declared malformed principal fails as
  an isolated entry with an escaped name, grants nothing, also when a stored
  row already carries it. Watch it fail first.
- [ ] 3.2 `EstateReconciler.reconcileGrant` validates first and renders the
  name escaped (`@Requirements GW_AUTH_0060`); 3.1 passes.

## 4. Exact claim matching (SVC_GW_AUTH_0066, SVC_GW_AUTH_0067)

- [ ] 4.1 `ClaimRoleMapperTests` / `ClaimRoleMappingTests`: padded and
  format-character values grant nothing, the lone exact string grants, the
  sweep's oracle compares untrimmed, and a padded configured claim value
  refuses construction. Watch them fail first.
- [ ] 4.2 `ClaimRoleMapper` stops trimming and validates configured values with
  the rule (`@Requirements GW_AUTH_0061`); 4.1 passes.

## 5. Documentation

- [ ] 5.1 `reference/api/roles.md`, `reference/configuration.md`,
  `guides/identity-providers.md`, `guides/declarative-estate.md`,
  `guides/delegated-administration.md`; `mkdocs build --strict` passes.

## 6. Gates and evidence

- [ ] 6.1 `mutants.sh` kills every mutant; its negative control exits nonzero.
- [ ] 6.2 Every AGENTS.md gate fresh after the last code edit, recorded in
  `evidence.md` with the commit SHA.
