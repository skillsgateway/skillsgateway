# Evidence — remove-roles-enabled-toggle

Commit under test: recorded at the head of this branch when the gauntlet ran
(see the PR's Evidence section for the SHA).

`spec approval: not obtained (autonomous run)`. The owner approved the decision —
remove the toggle, always enforce, fail closed on bootstrap — and Decision 6
(remove `/api/me.rolesEnabled`) explicitly. No written executable spec was
reviewed before implementation, so confidence rests on the adversarial cases and
the mutation proofs below rather than on sign-off. The other three open questions
were resolved on the design's own reasoning and are recorded in `design.md`.

Local runner notes: `TESTCONTAINERS_RYUK_DISABLED=true` (Ryuk cannot start on
rootless Podman) and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock`
(the Floci dev service bind-mounts the docker socket). Neither is needed in CI.

## The defect, proved before it was fixed

`RolesAlwaysEnforcedTests`, written against the unchanged code:

```
[ERROR] RolesAlwaysEnforcedTests.a_session_holding_no_role_is_refused_with_no_enforcement_property_anywhere:43
        Status expected:<403> but was:<201>
[ERROR] RolesAlwaysEnforcedTests.the_session_endpoint_no_longer_reports_an_enforcement_flag:67
        Expected no value at JSON path "$.rolesEnabled" but found: false
```

A session holding no role registered a marketplace, in the shared default
context — which is what an operator gets by installing the gateway and changing
nothing.

## Gauntlet

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | **BUILD SUCCESS** — 351 tests, 0 failures; 234 files spotless-clean; 0 checkstyle violations |
| `(cd src/main/frontend && pnpm test:stories)` | 3 files, 6 tests passed |
| `(cd src/main/frontend && pnpm e2e)` | 13 passed |
| `reqstool status local -p docs/reqstool` | **134/134 complete · 0 incomplete · PASS** |
| `openspec validate --all --strict` | 28 passed, 0 failed |
| `mkdocs build --strict` | clean |
| `./mvnw -Pnative -DskipTests native:compile` | not runnable locally (no GraalVM); **run in CI instead — see below** |

## Mutation proofs

The guard was written before its tests, so its tests are only worth what their
ability to fail is worth. Both refusals were removed in turn:

| Mutant | Result |
| --- | --- |
| Bootstrap refusal disabled (`if (false && !administrable(...))`) | 2 failures — `a_gateway_with_no_configured_administrator_refuses_to_start`, `a_configuration_naming_only_a_lesser_role_is_still_no_administrator` |
| Removed-property refusal disabled | 3 failures — every `the_removed_property_*` case, including the environment-variable one |

**The one that matters most (task 7.7).** Adding
`skills-gateway.roles.admins=user,root,alice` to the shared test context makes the
default principal an administrator, which could have blunted the suite's ability
to catch a *missing* authorization check. It has not: removing
`roleService.requireAdmin(authentication)` from `AdminController.registerMarketplace`
still fails the deny-by-default walk, because that walk lives in
`RoleEnforcementTests` — its own context, its own admin (`root`), and a principal
that is not one.

```
[ERROR] RoleEnforcementTests.a_no_role_session_is_refused_every_mutation_and_privileged_read...:133
        Status expected:<403> but was:<422>
```

422 rather than 403 is the tell: the request reached body validation, meaning it
got past an authorization check that was no longer there.

## Adversarial coverage

- A no-role session is refused every mutation, the audit surface and the grants
  API, with no enforcement property present anywhere (`SVC_GW_0138`).
- A configuration naming no administrator refuses to start, and the refusal names
  all three configuration paths and the escape hatch. A mapping or declared grant
  resolving only a *lesser* role is still no administrator — without that case the
  check would be satisfied by any mapping at all (`SVC_GW_0139`).
- The removed property is refused set to `false`, to `true`, and as the real
  environment variable `SKILLSGATEWAY_ROLES_ENABLED` through a genuine
  `SystemEnvironmentPropertySource` — not `withPropertyValues`, which installs
  literal names and would have proved only that an oddly-named property is absent
  (`SVC_GW_0140`).
- The escape hatch's principal performs an administrative mutation and is
  attributed to `dev-insecure-auth`, not to `config`. Both impersonation routes are
  closed separately, because a single test would pass with either condition
  removed: a principal named `dev` with the hatch **off** holds nothing, and an
  identity-provider session named `dev` gains nothing by that path (`SVC_GW_0141`).

## No SVC lost

Two suites verified a state that no longer exists. Neither was deleted before its
coverage moved:

- `RolesDisabledTests` (`SVC_GW_0068`) — deleted. `SVC_GW_0068` remains carried by
  `RoleEnforcementTests`, whose route-table completeness walk is the assertion that
  actually protects the surface. Read plainly, the deleted test asserted that the
  default deployment had no authorization.
- `ClaimRolesDisabledTests` (`SVC_GW_0098`) — became
  `ClaimMappedRoleIsEnforcedTests`, asserting the opposite half: the mapped auditor
  reads the ledger and is refused an admin mutation. `SVC_GW_0098` is also carried
  by `ClaimRoleMappingTests`, `ClaimRoleMapperTests` and the e2e suite.
- `MachineCredentialAdminTests` (`SVC_GW_0130`) kept its SVC and lost only its
  premise: it ran with enforcement off so that a check which still refused stood
  out. It now pins the narrower thing that survives — provisioning takes the
  administrative role and not a lesser one.

## Things found along the way

**The documented type-regeneration command cannot run in this repository.**
`openapi-typescript@7.13.0` throws against the installed `typescript@7.0.2`:

```
TypeError: Cannot read properties of undefined (reading 'createKeywordTypeNode')
    at .../openapi-typescript/dist/lib/ts.mjs:11:28
```

That is the exact command `OpenApiContractTests` prints when the contract is
stale, so anyone who touches the API surface will hit it. `types.gen.ts` here was
regenerated with the same `openapi-typescript` 7.13.0 against a pinned
`typescript@5.9.3` in an isolated directory, and the result diffed against the
committed file: **11 diff lines, all three of them intended** — the `/api/me`
description, the `source` enum gaining `dev-insecure-auth`, and `rolesEnabled`
going away. Nothing else moved, so it is a faithful regeneration rather than a
different generator's output. Filed separately; it is not this change's to fix.

**A self-inflicted break worth recording.** The first `run-e2e.sh` edit put
comment lines *inside* a backslash line-continuation chain, which silently
truncated the environment the gateway starts with; the symptom was Boot failing
on a missing datasource, nothing to do with roles. Caught by the e2e gate, fixed
by moving the comment above the block.

## Native image — run in CI, and it found a real break

The local step could not run (no GraalVM on this machine), so the **Native image**
workflow was dispatched against the branch rather than left unverified. It failed,
and not for a native-image reason:

```
Caused by: java.lang.IllegalStateException:
  Authorization is always enforced, and this gateway has no administrator.
    at dev.skillsgateway.server.roles.RoleBootstrapGuard.<init>(RoleBootstrapGuard.java:39)
```

The workflow's container smoke test starts the image with datasource settings and
nothing else, so under the new rule the gateway correctly refused to boot and the
health probe never connected. The guard did exactly what it is for; the smoke test
was configuring a deployment that is no longer valid.

Fixed here by naming an administrator in that step. It is part of this change
rather than a workflow fix in passing: this change is what makes the old
configuration insufficient.

**Nothing else would have caught it.** The smoke test exists only in `native.yml`,
which does not run on pull requests, so every JVM gate can be green — as all of
them were — while the packaged container cannot start. That is worth remembering
next time a change touches startup.

## The original gap, for the record

The gauntlet's native step **did not run**, and the change is claimed with that
gap stated rather than glossed:

```
[ERROR] native-image is not installed in your JAVA_HOME. This probably means that
the JDK at '/Users/.../java/current' is not a GraalVM distribution.
```

Worse, CI does not close it for this branch: `.github/workflows/native.yml`
triggers on `push` to `main`, a weekly schedule, `workflow_dispatch` and
`workflow_call` — **not on pull requests**. So the first native build containing
this change will be the one that runs after it merges to `main`.

The exposure is small and specific. The new bean is constructor-injected with
`Environment` and `SkillsGatewayProperties`, both already used by
`DevInsecureAuthGuard` in the same way and therefore already reachable in the
native build; the change adds no reflection, no resource loading and no new
dependency. The realistic native-only failure mode for a configuration-properties
record is a binding that works on the JVM and finds nothing native — which would
surface here as a gateway refusing to start with "has no administrator" on a
correctly configured deployment.

Two ways to close it before it can bite, in preference order: dispatch the Native
image workflow manually against this branch before merging, or watch the first
post-merge run on `main` and be ready to revert. Adding `pull_request` to that
workflow's triggers is worth considering on its own merits, and is not this
change's to make.
