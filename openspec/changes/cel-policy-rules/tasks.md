# Tasks: cel-policy-rules

## 1. Traceability (SSOT first)

- [ ] 1.1 GW_0089–GW_0092 in `docs/reqstool/requirements.yml`.
- [ ] 1.2 SVC_GW_0089–SVC_GW_0092 in
      `docs/reqstool/software_verification_cases.yml`.

## 2. Dependency and schema

- [ ] 2.1 `dev.cel:cel` with `<cel.version>` property in `pom.xml`.
- [ ] 2.2 `policy_rules` table in `V1__init.sql` (unique non-blank name,
      non-blank expression, enabled, created/updated attribution),
      commented per house style.

## 3. Policy core (pure, exhaustively unit-testable)

- [ ] 3.1 `policy/CelPolicy`: the declared variable environment (snapshot,
      files, plugins, skills), write-time `compile(expression)` enforcing
      result type bool, bounded `evaluate(compiled, facts)`; comprehension
      iteration cap (SVC_GW_0089, SVC_GW_0090).
- [ ] 3.2 `policy/SnapshotFacts`: facts builder over the pinned commit's
      tree via JGit — file inventory with fail-closed cap, plugins from
      the manifest, skills with `tools` parsed from SKILL.md YAML
      frontmatter (SafeConstructor; list and comma-string forms; malformed
      frontmatter is a build failure) (SVC_GW_0090, `@Requirements` on the
      implementing methods).

## 4. Rule lifecycle

- [ ] 4.1 `policy/PolicyRule` record + `policy/PolicyRuleRepository`
      (JdbcClient, RETURNING *).
- [ ] 4.2 `policy/PolicyRuleService`: create/update/delete/list with name
      rules, write-time compilation (422 on failure), duplicate 409,
      ledger events `policy-rule-created/-updated/-deleted`; shared by API
      and reconciler (SVC_GW_0089, `@Requirements({"GW_0089"})`).

## 5. The gate

- [ ] 5.1 `policy/PolicyGate.enforce(snapshot, marketplaceName, reviewer)`:
      load enabled rules, build facts (failure denies), evaluate each
      (true or error denies), append one `policy-denied` ledger entry per
      deciding rule, throw `PolicyDeniedException` naming all of them
      (`@Requirements({"GW_0090","GW_0091"})`).
- [ ] 5.2 Wire into `ApprovalService.doApprove` after the vetting gate,
      before `decide`; `@ExceptionHandler(PolicyDeniedException.class)` in
      `AdminController` → 409 ProblemDetail with `denials` property.

## 6. API

- [ ] 6.1 `policy/PolicyController` (`/api/policy`): POST/GET `rules`,
      PUT/DELETE `rules/{name}` (admin), POST `playground`
      (approver-of-snapshot; zero writes) with OpenAPI annotations; new
      `Policy` tag in `api/OpenAPI.java` (SVC_GW_0089, SVC_GW_0092).
- [ ] 6.2 Classify all five routes in `RoleEnforcementTests`
      (`ROLE_GATED_MUTATIONS` / `PRIVILEGED_READS`).
- [ ] 6.3 Regenerate `openapi.json` + `types.gen.ts`; frontend typecheck.

## 7. Estate (continuous obligation #65)

- [ ] 7.1 `SkillsGatewayProperties.Estate.policyRules` +
      `DeclaredPolicyRule(name, description, expression, enabled)`.
- [ ] 7.2 `EstateReconciler.reconcilePolicyRule` via `PolicyRuleService`,
      kind `policy-rule` in `EstateReconciliation.Entry` schema
      (SVC_GW_0089).

## 8. Tests (old-coder Tier 3; RED observed before GREEN)

- [ ] 8.1 Unit: `CelPolicyTests` (compile/type/eval/error/iteration-cap),
      `SnapshotFactsTests` frontmatter forms + malformed + cap
      (SVC_GW_0090 support).
- [ ] 8.2 `PolicyRuleApiTests` (`AbstractGatewayTest`): CRUD happy paths,
      422 on syntax/type errors, duplicate 409, ledger entries, unknown
      rule 404 (SVC_GW_0089, `@SVCs`).
- [ ] 8.3 `PolicyEnforcementTests`: matching rule denies with 409 naming
      the rule — snapshot stays held, published tip unchanged; disabled
      and non-matching rules do not deny; runtime-error rule denies;
      comprehension bomb denies within the time budget; malformed
      frontmatter denies under a tools rule; `policy-denied` ledger
      entries per deciding rule (SVC_GW_0090, SVC_GW_0091).
- [ ] 8.4 `PolicyPlaygroundTests`: matched/false/error answers against a
      real snapshot; identical ledger row count, snapshot state and served
      tip before/after, including error paths (SVC_GW_0092).
- [ ] 8.5 Estate: extend `EstateReconciliationTests` with declared rules —
      created, converged no-op, invalid expression isolated failure
      (SVC_GW_0089).
- [ ] 8.6 Gauntlet: manual mutation pass over `PolicyGate`/`CelPolicy`
      (fail-open mutants killed), adversarial pass, evidence.md.

## 9. Docs (same PR)

- [ ] 9.1 ADR `docs/decisions/0004-embedded-cel-for-policy-rules.md`;
      architecture.md policy-engine note updated to reference it.
- [ ] 9.2 `docs/manual/guides/policy-rules.md` (variables reference,
      examples, playground, fail-closed semantics, estate block).
- [ ] 9.3 `docs/manual/reference/api/policy.md` + mkdocs nav;
      `reference/configuration.md` estate `policy-rules`; glossary entry;
      trust-boundaries note.

## 10. Finish

- [ ] 10.1 Full gates fresh after last edit; `evidence.md` with pasted
      tails + commit SHA.
- [ ] 10.2 Archive the change (final commit of the PR).
