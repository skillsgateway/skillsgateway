## Context

See `proposal.md` — Why. The relevant constraint here is that
`docs/reqstool/` is the SSOT and code carries `@Requirements` / `@SVCs`
annotations pointing at it, so a stale requirement is not a documentation
defect the way a stale manual page is: it is the authoritative statement of
what the product must do, and it currently permits a mode
`GW_AUTH_0025 — Authorization on the web surface is always enforced` forbids.

Two requirements already carry the behavior `GW_AUTH_0010`'s stale clauses
describe the absence of:

- `GW_AUTH_0025 — Authorization on the web surface is always enforced`
- `GW_AUTH_0026 — A gateway with no administrator refuses to start`

## Goals / Non-Goals

**Goals:**

- `GW_AUTH_0010` and `SVC_GW_AUTH_0010` describe shipped behavior, with no
  residual reference to a configuration switch.
- `SVC_GW_AUTH_0010` keeps its route-table completeness assertion in substance.
- The published OpenAPI document stops describing a conditional that cannot occur.

**Non-Goals:**

- Changing any behavior, test assertion, or authorization outcome.
- Re-opening whether removing the switch was right. That was decided and shipped.
- The `docs/manual/` half of the residue — already clean.

## Decisions

**Narrow `GW_AUTH_0010` rather than deprecate it and add a successor.**
The requirement is still the right requirement: three roles, composing upward,
deny-by-default at the REST API. Only the conditional is wrong. A successor id
would strand every `@Requirements({"GW_AUTH_0010"})` annotation and read, to a
future auditor, as though the role model itself had been replaced. Rejected.

**Replace the rationale's compatibility argument by pointing at
`GW_AUTH_0026`, not by restating it.** `GW_AUTH_0025`'s rationale already argues
this at length ("The compatibility argument that justified defaulting off … is
answered instead by refusing to start when no administrator is configured").
Repeating it inside `GW_AUTH_0010` gives two copies that drift. Cross-reference.

**Proposed replacement text** — the shape, for review before it is written:

> **description**: The system shall provide three delegated-administration roles
> for the web surface — a global admin, an approver scoped to one marketplace,
> and a read-only auditor, composing upward so an admin may do everything either
> other role may — and shall refuse with an authorization error every REST API
> mutation and every audit-ledger and operational-listing read to a session
> holding no applicable role, while leaving the browsing surface and a session's
> own token management open to any authenticated session.
>
> **rationale**: The portal's login was the only privilege there was, so
> delegating curation needs rights that are granted rather than implied by
> authentication, and deny-by-default means a privileged endpoint added without a
> classification fails shut rather than open. Enforcement is unconditional
> (`GW_AUTH_0025`); the compatibility concern that a deployment could be locked
> out of its own gateway is answered at startup instead (`GW_AUTH_0026`).

The description is the original with two clauses deleted and "shall, once the
switch is enabled, refuse" collapsed to "shall refuse" — everything the
requirement actually specifies is preserved verbatim.

**`SVC_GW_AUTH_0010`: delete the second gateway, keep the walk.** The GIVEN
drops "and a second gateway left at the disabled default"; the WHEN drops the
trailing "while on the disabled gateway …"; the THEN drops "and on the disabled
gateway everything works exactly as before enforcement existed". The route-table
completeness clause — "both sets asserted complete against the running
application's own route table, and every read classified as privileged, scoped to
a marketplace, scoped to the session, or open" — is untouched. This matches what
`RoleEnforcementTests` already does, so no test changes.

**Bump `revision` on both.** `GW_AUTH_0010` is still `0.1.0` and has never been
revised despite `remove-roles-enabled-toggle` planning it; `SVC_GW_AUTH_0010` is
at `0.2.0`.

## Risks / Trade-offs

**A reqstool text edit is invisible to every gate.** No test fails if the
description is wrong, which is exactly how `remove-roles-enabled-toggle` marked
tasks 2.6 and 2.10 done without the edits landing. → The apply step greps for
the removed strings across `docs/reqstool/`, `src/main/java/` and
`openspec/specs/` and records a zero-hit result in `evidence.md`. That check is
cheap, and it is the one thing that would have caught the original miss.

**The `@ApiResponse` strings reach the published contract.** → Description-only;
`oasdiff` treats it as non-breaking, and the PR title carries no `!`. The
Breaking change detection check is the verification.

**Narrowing a requirement could look like weakening one.** → It removes a
permitted *weaker* mode, so the specified behavior gets stricter, not looser. No
SVC loses coverage: `SVC_GW_AUTH_0010` keeps its walk, and the disabled-gateway
case it names was deleted with `RolesDisabledTests` in #210.
