## Why

`skills-gateway.roles.enabled` was removed by the archived change
`remove-roles-enabled-toggle` (PR #210): authorization on the web surface is now
always enforced, and the gateway refuses to start without an administrator. Four
texts still describe the removed switch — including `GW_AUTH_0010 — Scoped admin
roles with deny-by-default enforcement`, which is the requirements SSOT and is
what code traces to, and four `@ApiResponse` strings that ship in the published
OpenAPI document.

That change's own `tasks.md` planned these edits and marked them done:

- **2.6** "Revise GW_AUTH_0010's description and rationale: drop the
  configuration switch and the 'defaults to off with every authorization check
  passing' clause … Bump `revision`." — `[x]`, but the clause is still there and
  `revision` is still `0.1.0`.
- **2.10** "Revise SVC_GW_AUTH_0010 — remove the second, disabled gateway from
  GIVEN/WHEN/THEN." — `[x]`, but all three of its disabled-gateway clauses are
  still there.

So this is not new scope. It is the unfinished tail of a merged change, and the
requirement currently permits a mode the product deliberately removed for a
security reason.

## What Changes

No behavior changes. Every edit is text catching up to shipped code.

- **`GW_AUTH_0010`** — drop "behind a configuration switch that defaults to off
  with every authorization check passing" and the "once the switch is enabled"
  conditional from the description, so it states enforcement unconditionally.
  Replace the rationale's compatibility argument ("defaulting the switch to off
  means an upgrade can never lock an existing deployment out of its own
  gateway") with the bootstrap refusal that replaced it, cross-referencing
  `GW_AUTH_0026`. Bump `revision`.
- **`SVC_GW_AUTH_0010`** — remove the second, disabled gateway from GIVEN, WHEN
  and THEN. The route-table completeness assertion is **preserved in substance**:
  the no-role walk must still assert its route set complete against the running
  application's own route table. Bump `revision`.
- **Four `@ApiResponse` 403 descriptions** reading "Enforcement is enabled and
  the caller is not an admin" → the unconditional form, in
  `EstateController:71` and `RoleController:69`, `:95`, `:118`. These are
  description-only OpenAPI edits and are therefore **not breaking**.
- **`openspec/specs/admin-roles/spec.md`** — its Purpose paragraph says
  "deny-by-default enforcement at the REST API once enabled"; drop the
  conditional.

Out of scope: the `docs/manual/` half of this residue, already cleaned up
separately — zero occurrences remain there.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

Scope widened during apply — see **What the sweep found** below.

- `admin-roles`: `GW_AUTH_0010` narrows — it no longer specifies a
  configuration switch that disables enforcement, and its verification case no
  longer covers a disabled gateway. `GW_AUTH_0013` loses "while enforcement is
  enabled" and the clause requiring the session identity endpoint to report
  whether enforcement is enabled. `GW_AUTH_0023` loses "once role enforcement is
  enabled" and "whether or not role enforcement is enabled".
- `admin-api`: `GW_AUTH_0022` loses "This enforcement shall not depend on
  whether role enforcement is enabled".
- `snapshot-preview`: `GW_INGEST_0015` loses "while role enforcement is
  enabled".

In every case the specified behavior converges on what ships; nothing the code
does changes.

## What the sweep found

Task 4.1 greps for the removed switch's vocabulary across `docs/reqstool/`,
`src/main/`, `docs/manual/` and `openspec/specs/`. It is the only verification a
text-only change has, and it found the residue to be roughly three times what
this proposal originally named. Two rounds of widening, both approved by the
owner during apply:

1. **Nine more source sites**, including the `@Operation` descriptions directly
   above three of the four `@ApiResponse` strings, and `RoleController`'s class
   javadoc describing grants staged "before flipping the switch".
2. **Four more requirements** — `GW_AUTH_0013`, `GW_INGEST_0015`,
   `GW_AUTH_0022`, `GW_AUTH_0023` — plus three `@ApiResponse` strings in
   `SnapshotPreviewController` and a `RoleService` javadoc.

**One of those is not a wording defect.** `GW_AUTH_0013` requires the system to
"report a session's effective roles and whether enforcement is enabled through
the session identity endpoint". `rolesEnabled` was removed from `/api/me` by
`remove-roles-enabled-toggle` and appears nowhere in `src/main/java/` or the
published contract, so the requirement currently specifies a field the product
does not serve. The clause is dropped rather than the field restored: there is
no enforcement state left to report, and adding API surface to report a constant
is exactly what the stop rule exists to refuse.

Three hits are correct and stay: `RemovedProperties`, `RemovedPropertyGuard` and
`SecurityConfig` name `skills-gateway.roles.enabled` because they refuse startup
when it is set, and `compatibility.md` and `configuration.md` document its
removal.

## Impact

| Area | Change |
| --- | --- |
| `docs/reqstool/requirements.yml` | `GW_AUTH_0010` description + rationale; `GW_AUTH_0013`, `GW_INGEST_0015`, `GW_AUTH_0022`, `GW_AUTH_0023` descriptions; `revision` bumps |
| `docs/reqstool/software_verification_cases.yml` | `SVC_GW_AUTH_0010` description, `revision` bump |
| `src/main/java/…/estate/EstateController.java` | two `@Operation` descriptions, one `@ApiResponse` |
| `src/main/java/…/roles/RoleController.java` | class javadoc, three `@Operation` descriptions, three `@ApiResponse` |
| `src/main/java/…/preview/SnapshotPreviewController.java` | three `@Operation` descriptions, three `@ApiResponse` |
| `src/main/java/…/auth/MachineTokenController.java` | class javadoc, one `@Operation` description |
| `src/main/java/…/adoption/AdoptionController.java` | class javadoc |
| `src/main/java/…/roles/RoleService.java` | class javadoc |
| `src/main/frontend/openapi.json`, `src/api/types.gen.ts` | regenerated; descriptions only |
| `openspec/specs/admin-roles/spec.md` | Purpose paragraph |
| Published OpenAPI document | 16 descriptions; additive-safe, non-breaking |

No production code path, no database schema, no configuration leaf, and no
portal surface is touched — every source edit is a javadoc or an OpenAPI
annotation string. `RoleEnforcementTests` already matches the revised
`SVC_GW_AUTH_0010` — the disabled-gateway case it describes was deleted by
#210 — so no test is weakened or removed by this change.
