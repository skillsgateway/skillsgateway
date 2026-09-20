## 1. Requirements SSOT

- [x] 1.1 Revise `GW_AUTH_0010`'s `description` in `docs/reqstool/requirements.yml` to the text in `design.md` → Decisions: delete "behind a configuration switch that defaults to off with every authorization check passing", and collapse "shall, once the switch is enabled, refuse" to "shall refuse". Every other clause stays verbatim.
- [x] 1.2 Revise `GW_AUTH_0010`'s `rationale`: drop the compatibility argument for defaulting off, and cross-reference `GW_AUTH_0025` for unconditional enforcement and `GW_AUTH_0026` for the startup refusal that answers it. Do not restate either rationale.
- [x] 1.3 Bump `GW_AUTH_0010`'s `revision` from `0.1.0`.
- [x] 1.4 Revise `SVC_GW_AUTH_0010` in `docs/reqstool/software_verification_cases.yml`: remove "and a second gateway left at the disabled default" from GIVEN, the trailing "while on the disabled gateway a no-role session performs privileged operations" from WHEN, and "and on the disabled gateway everything works exactly as before enforcement existed" from THEN. **Preserve the route-table completeness clause verbatim** — both sets asserted complete against the running application's own route table, every read classified.
- [x] 1.5 Bump `SVC_GW_AUTH_0010`'s `revision` from `0.2.0`.
- [x] 1.6 Confirm `RoleEnforcementTests.a_no_role_session_is_refused_every_mutation_and_privileged_read_but_keeps_browsing_and_tokens` (carrying `@SVCs({"SVC_GW_AUTH_0010"})`) still matches the revised case with **no test edit**. If it does not, stop — the revision was wrong, not the test.

## 2. Published API contract

**Scope correction, found during apply.** The proposal named four `@ApiResponse`
403 strings. A full sweep of `src/main/java/` found the same defect in nine more
places, including the `@Operation` descriptions immediately above three of those
four, and one that describes a state which cannot exist ("while disabled, grants
are inert data a deployment stages before enabling enforcement"). All of it ships
in the published OpenAPI document, and all of it is the same removed switch.
Fixing the 403 line while leaving the sentence above it wrong would be worse than
not touching the file, so the sweep is in scope. Still description-only, still
non-breaking, still no behavior change.

- [x] 2.1 `EstateController` — `:49` "Auditor or admin while role enforcement is enabled" and `:69` "Admin-only while role enforcement is enabled."; `:71` `@ApiResponse` 403 "Enforcement is enabled and the caller is not an admin".
- [x] 2.2 `RoleController` — the class javadoc at `:23`, which describes staging grants "before flipping the switch"; the `@Operation` descriptions at `:66`, `:92` and `:116`; the three `@ApiResponse` 403 strings at `:69`, `:95` and `:118`.
- [x] 2.3 `SnapshotPreviewController` — `:47`, `:66`, `:89`, all "Privileged while role enforcement is enabled".
- [x] 2.4 `MachineTokenController:128` — "Requires the admin role whether or not role enforcement is enabled" loses its "whether or not", which now names a distinction that does not exist.
- [x] 2.5 Regenerate the API types with `pnpm gen:api-types` (never `pnpm exec openapi-typescript`) so `src/main/frontend/src/api/types.gen.ts` stops carrying the same strings, and confirm only descriptions moved.

## 3. OpenSpec spec text

- [x] 3.1 `openspec/specs/admin-roles/spec.md` — drop "once enabled" from the Purpose paragraph's "deny-by-default enforcement at the REST API once enabled". Edited directly, per the specs instruction: a delta's Purpose is ignored for an existing capability.

## 4b. The four requirements the sweep found

- [x] 4b.1 `GW_AUTH_0013 — Audited role grant lifecycle`: drop "while enforcement is enabled" from "let only admins read and manage them through the API", and drop the trailing clause "and whether enforcement is enabled" from "shall report a session's effective roles and whether enforcement is enabled through the session identity endpoint". `rolesEnabled` was removed from `/api/me` by #210 and exists nowhere in `src/main/java/` or the published contract, so that clause specifies a field the product does not serve. Owner's decision during apply: drop the clause, do not restore the field. Bump `revision`.
- [x] 4b.2 `GW_INGEST_0015 — Snapshot file and tree preview`: "shall, while role enforcement is enabled, refuse these reads" → "shall refuse these reads". Bump `revision`.
- [x] 4b.3 `GW_AUTH_0022`: drop the trailing sentence "This enforcement shall not depend on whether role enforcement is enabled." Bump `revision`.
- [x] 4b.4 `GW_AUTH_0023`: "shall require both a scope and the corresponding role before an operation is permitted once role enforcement is enabled" loses "once role enforcement is enabled"; "whether or not role enforcement is enabled" is dropped from the machine-credential clause. Bump `revision`.
- [x] 4b.5 `SnapshotPreviewController:52`, `:70`, `:93` — `@ApiResponse` 403 "Role enforcement is enabled and the session holds no applicable role" → "The session holds no applicable role".
- [x] 4b.6 `RoleService:27` — javadoc "deny-by-default once enabled" loses the conditional.
- [x] 4b.7 Re-run `pnpm gen:api-types` after the OpenAPI document is regenerated by the build.

## 4. The check that would have caught the original miss

- [x] 4.1 Grep for the removed strings and confirm zero hits outside `openspec/changes/archive/`: `roles.enabled`, `Enforcement is enabled`, `role enforcement is enabled`, `switch that defaults to off`, `disabled gateway`, `while disabled`, `flipping the switch`, `once enabled` — across `docs/reqstool/`, `src/main/`, `docs/manual/` and `openspec/specs/`. This sweep is what found the nine extra sites in section 2; run it before believing the change is complete.
- [x] 4.2 Record the grep and its zero-hit output in `evidence.md`. This is the one verification a text-only change has; `remove-roles-enabled-toggle` marked its equivalent tasks done without it.

## 5. Gates and evidence

- [x] 5.1 `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify` — foreground, own call. `clean` is required or the generated annotation files truncate.
- [x] 5.2 `(cd src/main/frontend && pnpm test:stories)`
- [x] 5.3 `(cd src/main/frontend && pnpm e2e)`
- [x] 5.4 `reqstool status local -p docs/reqstool` — must end `PASS`, with `SVC_GW_AUTH_0010` still covered.
- [x] 5.5 `openspec validate --all --strict`
- [x] 5.6 `mkdocs build --strict`
- [x] 5.7 Write `openspec/changes/role-enforcement-texts/evidence.md`: the commands and pasted result tails of one final fresh run after the last edit, plus the commit SHA.
- [ ] 5.8 Open the PR with a non-`!` conventional-commit title and confirm **Breaking change detection** passes — the proof that description-only OpenAPI edits are additive.
