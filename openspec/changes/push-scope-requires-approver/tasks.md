# Tasks: push-scope-requires-approver

## 1. Requirements (reqstool)

- [ ] 1.1 Revise GW_FACADE_0007 (revision 0.2.0) to say a push scope is granted
  only to an administrator or an approver of that marketplace, and add
  SVC_GW_FACADE_0035 under it in `docs/reqstool/`. Verify with
  `openspec validate push-scope-requires-approver --strict`.

## 2. Mint-time authorization (SVC_GW_FACADE_0035)

- [ ] 2.1 `PushScopeMintingTests` (shared context, `@SVCs SVC_GW_FACADE_0035`),
  through `POST /api/v1/tokens` with `oidcLogin()`:
  - a no-role session asking for a push scope on a hosted marketplace gets
    `403`, and holds no token and no `token-created` ledger row afterwards;
  - an approver of A asking for A gets `201` with `pushScopes == [A]`, and that
    token publishes to A over `/publish`;
  - an approver of A asking for A and B gets `403`, and no token is issued;
  - an administrator asking for B gets `201`;
  - a no-role session asking for no push scope still gets `201`.
  Watch every refusal case fail first, against unchanged code.
- [ ] 2.2 `TokenController.create` calls `roleService.requireApprover` for each
  distinct push scope before issuing, with `@Requirements GW_FACADE_0007`, and
  adds `@ApiResponse 403`. Make 2.1 green without editing it. The existing
  `HostedPushTests` and `RoleEnforcementTests` must stay green unchanged.
- [ ] 2.3 A claim-derived administrator (`gw-admins`, in an
  `AbstractClaimMappingTest` subclass, `@SVCs SVC_GW_FACADE_0035`) mints a push
  scope with `201`. This proves the check honours claim roles. Verify that
  `ContextBudgetTests` and `ConfigSurfaceBudgetTests` are unchanged.
- [ ] 2.4 Regenerate `src/main/frontend/openapi.json` and `src/api/types.gen.ts`
  (`OpenApiContractTests` green).

## 3. Documentation

- [ ] 3.1 `reference/api/tokens.md` (who may request `pushScopes`, the `403`),
  `guides/publishing-first-party-skills.md` (Step 2 needs approver or admin
  standing), and `concepts/trust-boundaries.md` ("The credential is one nobody
  holds" names who can be given it). Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [ ] 4.1 Manual mutation: remove the check, swap `requireApprover` for a
  no-op, and check only the first scope. 2.1 must fail for each mutant.
  Restore and confirm with `git diff`.
- [ ] 4.2 Run every AGENTS.md gate fresh after the last code edit, and record
  the commands, result tails, the mutation results and the commit SHA in
  `evidence.md`.
