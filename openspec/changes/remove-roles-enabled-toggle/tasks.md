# Tasks: remove-roles-enabled-toggle

This change touches the authorization trust boundary, so the
`.claude/skills/old-coder` discipline applies throughout: the executable spec
(section 1) is written and approved **before** any implementation file is
touched, every new test is observed failing before the code that makes it pass
(section 4 gates sections 5–7), and the run is closed by a gauntlet and an
evidence report (sections 12–13). No task in section 5 onward may start while a
section-4 checkbox for the behaviour it implements is unticked.

## 1. Executable spec and owner sign-off (old-coder SPEC)

- [ ] 1.1 Write the executable spec to `openspec/changes/remove-roles-enabled-toggle/spec-approval.md`: the named test list for GW_0138–GW_0141 with concrete inputs and expected outcomes, plus the negative constraints that must survive (SVC_GW_0068's route-table completeness walk, SVC_GW_0110's refusal, approver scoping, the facade's and machine chain's independent authorization). Name it by absolute path in the message to the owner.
- [ ] 1.2 Resolve the four Open Questions in `design.md` with the owner — Decision 3's strictness, Decision 6 (`/api/me.rolesEnabled` removed vs. kept deprecated), whether this PR settles issue #121, and the lifetime of the removed-property refusal. Fold the answers into `design.md` and `spec-approval.md` visibly (answers change the spec; they are not approval of it).
- [ ] 1.3 Re-present the revised spec and obtain explicit approval. Record the approving words, or record `spec approval: not obtained (autonomous run)` in `evidence.md` and claim correspondingly lower confidence.
- [ ] 1.4 Confirm no new dependency is needed (the guard uses `Environment` and `SkillsGatewayProperties` only); state that in the spec so approval covers the setup plan.

## 2. Requirements (reqstool SSOT, before any code)

- [ ] 2.1 Add GW_0138 to `docs/reqstool/requirements.yml` — authorization on the web surface is always enforced, with no configuration that disables it. Categories `["security"]`.
- [ ] 2.2 Add GW_0139 — refuse to start when no configured principal could hold the administrative role, with a refusal naming every configuration path that would resolve it.
- [ ] 2.3 Add GW_0140 — refuse to start when the removed enforcement property is set in any relaxed form, naming the removal.
- [ ] 2.4 Add GW_0141 — the development escape hatch confers the administrative role on its synthetic principal, attributed to its own source, and confers nothing while the escape hatch is off or to an identity-provider session that merely shares the synthetic name.
- [ ] 2.5 Add SVC_GW_0138–SVC_GW_0141 to `docs/reqstool/software_verification_cases.yml` with GIVEN/WHEN/THEN covering the adversarial cases from section 7.
- [ ] 2.6 Revise GW_0068's description and rationale: drop the configuration switch and the "defaults to off with every authorization check passing" clause; the rationale's compatibility argument is replaced by the bootstrap refusal (cross-reference GW_0139). Bump `revision`.
- [ ] 2.7 Revise GW_0071: drop "while enforcement is enabled" and the "whether enforcement is enabled" clause of the session identity endpoint. Bump `revision`.
- [ ] 2.8 Revise GW_0130: drop the "once role enforcement is enabled" and "whether or not role enforcement is enabled" qualifiers while keeping the substance (scope ∧ role; the administrative role for minting, listing and revoking). Bump `revision`.
- [ ] 2.9 Revise GW_0129: drop the closing sentence "This enforcement shall not depend on whether role enforcement is enabled" and the rationale sentence that leans on the default-off flag. Bump `revision`.
- [ ] 2.10 Revise SVC_GW_0068 — remove the second, disabled gateway from GIVEN/WHEN/THEN. **Preserve the route-table completeness assertion in substance**: the no-role walk must still assert its route set complete against the running application's own route table (this is the armor named in `design.md` → Risks). Bump `revision`.
- [ ] 2.11 Revise SVC_GW_0069, SVC_GW_0070, SVC_GW_0098, SVC_GW_0129 and SVC_GW_0130 to drop "role enforcement enabled/disabled" preconditions, which are no longer variables. Bump each `revision`.
- [ ] 2.12 Run `reqstool status local -p docs/reqstool` and confirm the new ids are registered and unimplemented (expected FAIL at this point; note reqstool prints FAIL but exits 0).

## 3. Verify no id collision

- [ ] 3.1 Confirm GW_0132–GW_0137 and their SVCs are still claimed by the in-flight change `fix-discarded-ref-update-results` and that nothing in this branch uses them; confirm GW_0138–GW_0141 are unused anywhere in `docs/reqstool/`, `openspec/` or `src/`.

## 4. RED — prove every new test fails first (old-coder RED)

Each checkbox is one observed failing run, recorded in `evidence.md` with the
command and the failure tail. Tests written here, implementation in 5–6.

- [ ] 4.1 Write `RolesAlwaysEnforcedTests` (SVC_GW_0138): a no-role OIDC session is refused on every mutation and on the audit surface with **no** `skills-gateway.roles.enabled` present anywhere. Observe it fail against the current default-off code.
- [ ] 4.2 Write `RoleBootstrapGuardTests` (SVC_GW_0139): a context with no `roles.admins`, no `admin` claim mapping and no declared `admin` estate grant refuses to start, and the refusal text names all three paths plus the escape hatch. Observe failure (today it starts).
- [ ] 4.3 Write the three positive bootstrap cases in the same class — `roles.admins` non-empty, an `admin` claim mapping, a declared `admin` estate grant — each starting cleanly. Observe failure.
- [ ] 4.4 Write `RemovedRolesPropertyTests` (SVC_GW_0140): a context setting `skills-gateway.roles.enabled=false` refuses to start naming the removal; the same for `=true`; the same for the relaxed environment form `SKILLSGATEWAY_ROLES_ENABLED`. Observe failure.
- [ ] 4.5 Extend `DevAuthTests` (SVC_GW_0141): under `dev-insecure-auth=true` the `dev` principal performs an administrative mutation and `/api/me` reports admin with source `dev-insecure-auth`. Observe failure.
- [ ] 4.6 Write the two adversarial cases for GW_0141: (a) with `dev-insecure-auth` off, a principal named `dev` holds nothing and is refused; (b) an `OidcUser` whose name is `dev` holds nothing from the escape-hatch path. Observe both fail.
- [ ] 4.7 Write the adversarial bootstrap-skip case: `dev-insecure-auth=true` **plus** a configured identity provider still refuses to start (the existing `DevInsecureAuthGuard` refusal must win, and the bootstrap skip must not open a path around it). Observe the current behaviour and record whether this is pre-existing armor kept as regression cover — if it passes immediately, break `DevInsecureAuthGuard` with a throwaway mutant, watch it fail, restore, and record that in `evidence.md`.
- [ ] 4.8 Add `@SVCs` annotations to each test method written above: `SVC_GW_0138`, `SVC_GW_0139`, `SVC_GW_0140`, `SVC_GW_0141`. Test method names stay snake_case identifiers.

## 5. GREEN — remove the property (old-coder GREEN)

- [ ] 5.1 `SkillsGatewayProperties.Roles`: delete the `enabled` component and its compact-constructor default; update the record javadoc — it currently documents the default-off rationale that no longer exists.
- [ ] 5.2 `RoleService`: delete `enabled()` and the `if (!enabled()) return;` prologue from `requireAdmin`, `requireAuditor`, `requireApprover` and `requireApproverOf`; rewrite the class javadoc (it documents the default-off consequence).
- [ ] 5.3 `RoleService`: collapse `requireAdminRegardlessOfEnforcement` into `requireAdmin` and delete the method; keep its `@Requirements({"GW_0130"})` coverage by annotating `requireAdmin` with `GW_0068`, `GW_0130` and `GW_0138`.
- [ ] 5.4 Update the callers in `MachineTokenController` (mint, list, revoke) to `requireAdmin`.
- [ ] 5.5 Add `@Requirements({"GW_0138"})` to the `require*` methods that now carry unconditional enforcement.
- [ ] 5.6 `ClaimRoleMapper`: update the javadoc sentence about the `dev-insecure-auth` principal to state the new interaction (it derives nothing from claims, and its admin role comes from `RoleService`, GW_0141).
- [ ] 5.7 `SecurityConfig`: remove the javadoc sentences on the machine chain and the web chain that explain why they do not consult `skills-gateway.roles.enabled`.
- [ ] 5.8 Grep `src/` for any remaining `roles.enabled`, `rolesEnabled`, `enabled()` on `Roles`, or `RegardlessOfEnforcement` and confirm zero hits outside the removed-property guard.

## 6. GREEN — the two startup refusals and the dev principal

- [ ] 6.1 Add the guard bean in `dev.skillsgateway.server.roles` (same package as `RoleService`, sibling in spirit to `DevInsecureAuthGuard`), constructor-injected with `SkillsGatewayProperties` and `Environment`, throwing `IllegalStateException` from its constructor.
- [ ] 6.2 Implement the removed-property refusal (GW_0140): `Environment.containsProperty("skills-gateway.roles.enabled")` — which resolves the relaxed and environment-variable forms — with a message naming the removal, the release, and that enforcement is now unconditional. Annotate `@Requirements({"GW_0140"})`.
- [ ] 6.3 Implement the bootstrap refusal (GW_0139): satisfied by a non-empty `roles.admins`, any `roles.mappings[*]` with `role: admin`, or any `estate.grants[*]` with `role: admin`; skipped when `devInsecureAuth()` is true. No database access. Annotate `@Requirements({"GW_0139"})`.
- [ ] 6.4 Write the refusal text following `design.md`'s draft and `DevInsecureAuthGuard`'s shape: what was detected, why it matters, each way to resolve it, property names in full.
- [ ] 6.5 `RoleService`: add the `EffectiveRole` source constant `dev-insecure-auth` and contribute a global `admin` role for the synthetic principal while `devInsecureAuth()` is true — only for a non-`OidcUser` principal whose name is exactly the synthetic name. Annotate `@Requirements({"GW_0141"})`. Extend the `@Schema(allowableValues=...)` on `EffectiveRole.source`.
- [ ] 6.6 Run the full Java suite; every test from section 4 goes green and nothing else regresses.

## 7. REFACTOR and adversarial hardening (old-coder REFACTOR)

- [ ] 7.1 Under green, with assertions frozen: tidy the guard, extract the bootstrap-path predicate, and confirm the suite is green before and after.
- [ ] 7.2 Replace `RolesDisabledTests` (currently carrying SVC_GW_0068) — the state it verifies no longer exists. Its SVC coverage moves to the revised SVC_GW_0068 walk in `RoleEnforcementTests`; the class is deleted only once that coverage is in place and observed. Never leave SVC_GW_0068 uncovered.
- [ ] 7.3 Replace `ClaimRolesDisabledTests` (SVC_GW_0098) the same way: the "mappings are reported but enforce nothing while the switch is off" case is replaced by a case asserting mappings enforce, folded into `ClaimRoleMappingTests` under SVC_GW_0098.
- [ ] 7.4 `AbstractGatewayTest`: add `skills-gateway.roles.admins=user` so the default `oidcLogin()` principal can administer, with a comment explaining why (the bootstrap check, and that negative suites override it). Verify Spring's subclass `@TestPropertySource` override still lets `RoleEnforcementTests` (`admins=root`) and the other role suites win.
- [ ] 7.5 Drop `skills-gateway.roles.enabled` from the `@TestPropertySource` of `RoleEnforcementTests`, `ClaimRoleMappingTests`, `EstateReconciliationTests`, `MachineRoleIntersectionTests`, `MachineApiScopeTests`, `RolesDisabledTests` and `ClaimRolesDisabledTests` (the last two as part of 7.2/7.3).
- [ ] 7.6 Update `MachineApiScopeTests` / `SVC_GW_0129` coverage: the walk that ran "with role enforcement disabled" now runs under enforcement with an explicitly admin-roled principal, so the assertion stays "no scope reaches an excluded endpoint" rather than becoming "no role does".
- [ ] 7.7 Re-run the SVC_GW_0068 route-table completeness walk and confirm it still fails when a `require*` call is deliberately removed from one controller method (throwaway mutant, restored) — this is the check that the section 7.4 base-class admin has not blunted the suite. Record it in `evidence.md`.

## 8. API and portal

- [ ] 8.1 `MeController.MeView`: remove `rolesEnabled` and its `@Schema`; update the `@Operation` description, which names it.
- [ ] 8.2 Update the `jsonPath("$.rolesEnabled")` assertions in `RoleEnforcementTests` (two) and delete them from the tests replaced in 7.2/7.3.
- [ ] 8.3 Regenerate `src/main/frontend/openapi.json` from `OpenApiDocsTests` → `target/openapi.json`, then `pnpm exec openapi-typescript openapi.json -o src/api/types.gen.ts`. Never hand-edit either.
- [ ] 8.4 Remove `rolesEnabled` from the `/api/me` fixture in `src/main/frontend/src/test/msw-handlers.ts`.
- [ ] 8.5 Grep `src/main/frontend/src` for `rolesEnabled` and confirm no component or query reads it.
- [ ] 8.6 Confirm `OpenApiContractTests` passes (the committed contract equals the served document) and note that oasdiff will classify the field removal as breaking, so the PR title must carry `!` and the `⚠️ BREAKING CONTRACT` label applies.

## 9. e2e and local run

- [ ] 9.1 `src/main/frontend/e2e/run-e2e.sh`: remove `SKILLSGATEWAY_ROLES_ENABLED=true`; keep `SKILLSGATEWAY_ROLES_CLAIM` and the `sg-gateway-admins → admin` mapping, and add a comment that the mapping is now what satisfies the bootstrap check, so removing it turns into a startup refusal.
- [ ] 9.2 Add or adjust one e2e assertion (`@SVCs SVC_GW_0138`, snake_case test title) proving a real login through the mock IdP reaches the admin surface and that `/api/me` no longer carries `rolesEnabled`.
- [ ] 9.3 Verify the documented local loop still works: `bootRun` with `skills-gateway.dev-insecure-auth=true` starts (bootstrap check skipped) and the `dev` principal can perform an administrative mutation in the portal.

## 10. Helm chart

- [ ] 10.1 `helm/skills-gateway/values.yaml`: delete the `IMPORTANT:` block about `skills-gateway.roles.enabled` defaulting to false and replace it with a note that authorization is always enforced and that an installation must name an admin (`roles.admins`, an `admin` claim mapping, or a declared estate grant) or the pod will refuse to start.
- [ ] 10.2 Remove `enabled: true` from the commented `config.skills-gateway.roles` example, leaving the `admins` list as the bootstrap example.
- [ ] 10.3 Grep `helm/` for any remaining `ROLES_ENABLED` / `roles.enabled` (templates, NOTES.txt, tests) and confirm zero hits.

## 11. Documentation (same PR — CLAUDE.md's docs-in-same-PR rule)

- [ ] 11.1 `docs/manual/guides/deploying-on-kubernetes.md`: delete the `!!! danger "Role enforcement is off by default"` admonition and replace it with the new startup behaviour — enforcement is unconditional, and a deployment that names no admin refuses to start with a message naming the three configuration paths. Also fix the env-var mapping example, which uses `SKILLSGATEWAY_ROLES_ENABLED` as its illustration (line ~127), and the `config:` example that sets `roles.enabled: true`.
- [ ] 11.2 `docs/manual/reference/configuration.md`: remove the `skills-gateway.roles.enabled` row; document the two startup refusals and the escape-hatch admin.
- [ ] 11.3 `docs/manual/guides/delegated-administration.md`: rewrite the "off by default" opening and the "set it to false and restart" rollback section; the rollback is now "grant the role", not "switch enforcement off".
- [ ] 11.4 `docs/manual/guides/identity-providers.md`: the "configure the mappings while the flag is still false" staging advice and the `rolesEnabled: true` sample response both go; replace the staging advice with configuring the mapping before first start.
- [ ] 11.5 `docs/manual/reference/api/roles.md`: remove the "while `roles.enabled=false` these endpoints are …" preamble.
- [ ] 11.6 `docs/manual/reference/api/index.md`: remove `rolesEnabled` from the `/api/me` example and the surrounding prose (two places).
- [ ] 11.7 `docs/manual/reference/api/tokens.md` and `reference/api/marketplaces.md`: drop the `roles.enabled=true` conditionals from the authorization notes.
- [ ] 11.8 `docs/manual/reference/portal.md`: drop the `roles.enabled=true` conditional from the portal's authorization description.
- [ ] 11.9 `docs/manual/concepts/trust-boundaries.md`: update both mentions (the filter-chain independence note and the "off by default so an upgrade never …" paragraph).
- [ ] 11.10 `docs/manual/concepts/glossary.md`: "Role grant" — remove "inert until `skills-gateway.roles.enabled=true`".
- [ ] 11.11 `docs/manual/architecture.md` (~line 491): remove "enabled, off by default".
- [ ] 11.12 `docs/manual/reference/compatibility.md`: add the sentence stating that the API-contract promise covers `/api/**` and does **not** currently extend to the `skills-gateway.*` configuration surface, the Helm values or the declarative estate, pointing at issue #121 as the open decision; record the removed-property refusal and that it is scheduled for removal at the next major.
- [ ] 11.13 Grep `docs/` for `roles.enabled`, `ROLES_ENABLED` and `rolesEnabled` and confirm zero remaining hits.
- [ ] 11.14 `mkdocs build --strict` passes (no broken anchors from the removed admonition or sections).

## 12. Gauntlet (old-coder GAUNTLET) — the full gate list

Run in this order after the last code edit; a container-runtime blip is a retry,
not a result.

- [ ] 12.1 `./mvnw -q spotless:apply`
- [ ] 12.2 `./mvnw clean verify` (Java + UI gates + packaged jar; needs Docker/Podman with Ryuk disabled)
- [ ] 12.3 `(cd src/main/frontend && pnpm test:stories)`
- [ ] 12.4 `(cd src/main/frontend && pnpm e2e)`
- [ ] 12.5 `reqstool status local -p docs/reqstool` — must end "PASS" (use the `clean` build above; incremental compilation truncates the generated annotation files)
- [ ] 12.6 `openspec validate --all --strict`
- [ ] 12.7 `mkdocs build --strict`
- [ ] 12.8 `./mvnw -Pnative -DskipTests native:compile` — the release path; confirms the new guard needs no native hints
- [ ] 12.9 Mutation run over `RoleService` and the new guard (a `mutation-run.sh` in this change directory, following the `2026-08-28-four-eyes-approval` precedent): every surviving mutant on an authorization decision is either killed or explained in `evidence.md`.

## 13. Evidence and archive

- [ ] 13.1 Write `openspec/changes/remove-roles-enabled-toggle/evidence.md`: the commit SHA, one fresh run of every command in section 12 with pasted result tails, the RED failure tails from section 4, the two throwaway-mutant records (4.7 and 7.7), the mutation summary, and the spec-approval line from 1.3. Map every spec behaviour and every negative constraint to a test, a gauntlet layer, or an explicit skipped-with-reason line.
- [ ] 13.2 PR body: **Evidence** section summarising `evidence.md`; title `fix!: always enforce authorization and refuse to start without an administrator`; `⚠️ BREAKING CONTRACT` label; the migration steps from `design.md`; and an explicit note that the compatibility page's "new prefix and a major" clause cannot be honoured literally (no `/api/v1` prefix exists yet, and the product is pre-1.0).
- [ ] 13.3 `/opsx:archive` this change into `openspec/specs/` as the final commit of the PR, after implementation and gates.
