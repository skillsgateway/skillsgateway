# Design: bootstrap-admins-principal-rule

## Context

See proposal.md, "Why". `RoleService` treats a principal as an administrator
when `skills-gateway.roles.admins` contains it, by exact equality.
`RoleBootstrapGuard` already reads that list at startup to refuse a gateway
with no administrator (`GW_AUTH_0026`). `RolePrincipals.problem` is the form
rule; `requireGrantable` adds the 255-code-point bound and throws a 422.

## Goals / Non-Goals

**Goals:** a configured administrator entry that no session could present
stops startup, with a message that says which entry and why.

**Non-goals:**

- Trimming. The rule refuses; it does not repair (as in `GW_AUTH_0060`).
- Other configured principals. Declared estate grants already pass the rule
  and fail as isolated entries; claim mapping values already refuse startup.

## Decisions

### Check in `RoleBootstrapGuard`, before anything else

The guard is the existing startup check over this list, so the refusal sits
beside "no administrator" rather than in a new bean. It runs before the
`dev-insecure-auth` skip, because a malformed entry is a mistake whatever mode
the gateway runs in, and before the "has an administrator" test, because a list
of only malformed entries is a more specific failure than an empty one.

*Alternative:* validate in the `Roles` record's compact constructor. Rejected:
a binding failure is wrapped in Spring's bind-error report, and the claim
mapping (#780) set the precedent of a plain `IllegalStateException` naming
`skills-gateway.roles.mappings[i]`.

### One rule: `RolePrincipals.grantProblem`

`requireGrantable` already combines the form rule with the length bound. That
combination moves into `grantProblem(String) -> Optional<String>`;
`requireGrantable` becomes a call to it plus the 422, and the guard calls it
too. No second validator exists.

### The message names the position, not the value

`skills-gateway.roles.admins[1] must not begin or end with whitespace`, plus
one sentence on why (matched exactly, would never match). A principal can be
an e-mail address, and a value with invisible characters is unreadable in a
log; the index is enough to find the line. Same shape as the claim-value
refusal.

## Risks / Trade-offs

- [A comma-separated value such as `SKILLS_GATEWAY_ROLES_ADMINS="ops, bob"`]
  → Spring's comma-delimited conversion trims each element, so that form still
  binds clean values; only an indexed or YAML-quoted padded entry is refused.
- [An existing deployment with a padded entry stops starting] → that entry
  never matched; failing loudly is the point. Pre-1.0, no compatibility owed.

## Migration Plan

None; configuration-only check, no schema change.
