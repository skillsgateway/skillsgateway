# Proposal: remove-roles-enabled-toggle

## Why

`skills-gateway.roles.enabled` defaults to `false`. The default lives in a Java
record's compact constructor (`SkillsGatewayProperties.Roles`) and appears
nowhere in `application.yaml`, so it is the default for **every** profile
including production. While it is false every `require*()` check in
`RoleService` returns without looking at anything, so any principal who
completes an OIDC login is effectively a full admin: they can register
marketplaces, approve snapshots, grant roles and read the whole ledger. The
gateway ships this posture and documents it as a `!!! danger` admonition in
`guides/deploying-on-kubernetes.md` — a danger admonition on a default is an
admission that the default is wrong, not a control.

The flag is also badly named in a way that guarantees the failure. It is
positively named for the secure state, so *absence* yields the insecure state;
`enabled` reads as a feature toggle for an optional capability when what
`false` means is "authorization is off". Its sibling escape hatch,
`skills-gateway.dev-insecure-auth`, does the opposite on every count: it names
the danger in the property itself, it logs a loud warning at startup, and
`DevInsecureAuthGuard` refuses to start when it detects a real deployment. The
roles flag has none of those.

The only argument for defaulting off was backward compatibility — "an upgrade
must never lock a deployment out of its own gateway". That argument expires
now and never returns: `0.1.0` is tagged but its release run was cancelled, so
no artifact was ever published and there is no deployed consumer to lock out.
The honest replacement for a default-off flag is a **bootstrap check**: refuse
to start when enforcement would leave nobody able to administer the gateway,
and say so.

## What Changes

- **BREAKING (configuration).** Delete `skills-gateway.roles.enabled`
  entirely. There is no opt-out property and no replacement: authorization on
  the web surface is always enforced. `RoleService.enabled()` and every
  `if (!enabled()) return;` short-circuit go with it, and
  `requireAdminRegardlessOfEnforcement` collapses into `requireAdmin` — the
  distinction it existed to draw no longer exists.
- **Fail closed at startup instead.** A new startup guard refuses to start
  when no configured principal could administer the gateway — no
  `skills-gateway.roles.admins` entry, no claim mapping granting `admin`, and
  no declared estate grant of `admin` — with a refusal that names the fix, in
  the shape `DevInsecureAuthGuard` already established.
- **Refuse rather than ignore the removed property.** Spring ignores unknown
  properties, so a deployment that still sets `roles.enabled=false` would be
  silently switched to enforcing and one that sets `=true` would silently do
  nothing. Both are silent reversals of an operator's explicit intent about a
  security posture, so a set `skills-gateway.roles.enabled` (in any relaxed
  form, environment variable included) is a startup refusal naming the removal.
- **Local development keeps working through the existing escape hatch.** While
  `skills-gateway.dev-insecure-auth=true`, its synthetic `dev` principal holds
  the administrative role — reported with its own source, so `/api/me` says
  where the privilege came from — and the bootstrap check is satisfied by the
  escape hatch itself. No second, quieter escape hatch is introduced.
- **BREAKING (API).** `/api/me` drops `rolesEnabled`: with enforcement
  unconditional the field is constantly `true` and answers a question that no
  longer has two answers. This is a field removal under
  `reference/compatibility.md` → "The API contract", so the pull request title
  declares the break and the `⚠️ BREAKING CONTRACT` label applies. See
  `design.md` → "Decision 6" for the alternative (keep it deprecated and
  always true) and why it is not preferred.
- New requirements **GW_0138** (authorization always enforced), **GW_0139**
  (refuse to start with no administrator), **GW_0140** (refuse to start when
  the removed property is set) and **GW_0141** (the escape hatch's principal
  administers, and only while the escape hatch is on), each with its SVC.
  Requirement ids start at GW_0138 rather than GW_0132 because the in-flight
  change `fix-discarded-ref-update-results`, on another branch, is claiming
  GW_0132–GW_0137; starting after it avoids a collision that neither branch
  would see until both merged.

## Capabilities

### New Capabilities

_None._ The behaviour belongs to capabilities that already exist.

### Modified Capabilities

- `admin-roles`: enforcement stops being conditional. GW_0068 and GW_0071 lose
  the configuration switch and its default-off semantics from their text;
  GW_0069, GW_0070 and GW_0098 lose the "role enforcement enabled" precondition
  from their verification cases (it is now the only state); GW_0130 loses the
  "whether or not role enforcement is enabled" qualifier that distinguished
  credential minting from everything else. Gains GW_0138, GW_0139 and GW_0140.
- `admin-api`: GW_0129 loses its closing sentence that the scope classification
  does not depend on whether role enforcement is enabled, and its verification
  case stops being conducted "with role enforcement disabled".
- `auth`: gains GW_0141 — the development escape hatch confers the
  administrative role on its synthetic principal, and confers nothing when the
  escape hatch is off.

## Impact

- **Backend**: `SkillsGatewayProperties.Roles` (drop `enabled`), `RoleService`
  (unconditional `require*`, collapse
  `requireAdminRegardlessOfEnforcement`, dev-principal admin with a new
  `EffectiveRole` source), `ClaimRoleMapper` (javadoc), `SecurityConfig`
  (javadoc on the machine chain), `MeController` (drop `rolesEnabled`), one new
  guard bean for the bootstrap and removed-property refusals.
- **API**: `/api/me` loses a field → `src/main/frontend/openapi.json`
  regenerated, `src/api/types.gen.ts` regenerated, oasdiff classifies a break,
  PR title must declare it.
- **Portal**: `msw-handlers.ts` fixture drops `rolesEnabled`; no component
  reads it today.
- **Tests**: `RolesDisabledTests` and `ClaimRolesDisabledTests` verify a state
  that will no longer exist and are replaced, not weakened —
  `RolesDisabledTests` currently carries SVC_GW_0068 and
  `ClaimRolesDisabledTests` SVC_GW_0098, so both SVCs' verification text is
  revised and re-covered rather than dropped. `AbstractGatewayTest` must
  bootstrap an admin for every context that mutates; `MachineApiScopeTests`,
  `MachineRoleIntersectionTests`, `RoleEnforcementTests`,
  `ClaimRoleMappingTests` and `EstateReconciliationTests` drop the property
  from their `@TestPropertySource`.
- **e2e**: `src/main/frontend/e2e/run-e2e.sh` drops
  `SKILLSGATEWAY_ROLES_ENABLED=true` and keeps its admin claim mapping, which
  is now what satisfies the bootstrap check.
- **Helm**: `values.yaml` — the `IMPORTANT:` note about the default and the
  commented `roles.enabled: true` example.
- **Docs** (same PR): `guides/deploying-on-kubernetes.md` (the `!!! danger`
  admonition is obsolete; the env-var mapping example names this property),
  `reference/configuration.md`, `guides/delegated-administration.md`,
  `guides/identity-providers.md`, `reference/api/roles.md`,
  `reference/api/index.md`, `reference/api/tokens.md`,
  `reference/api/marketplaces.md`, `reference/portal.md`,
  `concepts/trust-boundaries.md`, `concepts/glossary.md`, `architecture.md`,
  `reference/compatibility.md` (whether the configuration surface is covered by
  the API-contract promise — issue #121 leaves it undecided).
- **Requirements**: `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` — four additions and the revisions above.
- **Trust boundary**: this is the authorization boundary itself, so the
  `.claude/skills/old-coder` discipline applies: adversarial and negative
  tests, each new test proven to fail before the change lands, and an
  `evidence.md`.
- **Declarative estate**: no new API-managed runtime state, so
  `skills-gateway.estate.*` needs no extension; the estate's existing
  `grants` become a bootstrap path the new guard reads.
