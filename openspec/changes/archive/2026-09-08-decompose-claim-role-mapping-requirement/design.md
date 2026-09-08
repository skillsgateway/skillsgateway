# Design: decompose-claim-role-mapping-requirement

## Context

- `GW_AUTH_0015` — *Identity-provider claim to role mapping* — was 894
  characters, the shortest of the five remaining candidates, but its clauses
  map cleanly onto distinct methods: `ClaimRoleMapper.validated` (a private
  method run once, at construction), `ClaimRoleMapper.rolesFrom` (run on
  every request), and `MeController.me` (the reporting endpoint).
- Three test classes carry `SVC_GW_AUTH_0015`: `ClaimRoleMapperTests` (the
  mapper in isolation, away from HTTP — six methods), `ClaimRoleMappingTests`
  (integration-level, through a real Spring context and MockMvc — seven
  methods, one of which is actually `SVC_GW_ESTATE_0003` and untouched), and
  `ClaimMappedRoleIsEnforcedTests` (one method, a dedicated context proving
  enforcement). A Playwright e2e test also carries it, alongside
  `SVC_GW_AUTH_0025`.

## Decision 1 — three children along the code's own method boundaries

Unlike `GW_VETTING_0029` and `GW_APPROVAL_0004` on this branch, this
requirement's clauses already map one-to-one onto three real methods, so the
decomposition is close to mechanical:

| Child | Guarantee | Method |
| --- | --- | --- |
| `GW_AUTH_0015.1` | exact matching, union without widening, no-claims-no-role | `ClaimRoleMapper.rolesFrom`, `RoleService.effectiveRoles` |
| `GW_AUTH_0015.2` | malformed mapping refuses startup | `ClaimRoleMapper.validated` |
| `GW_AUTH_0015.3` | identity endpoint reports the source | `MeController.me`, `RoleService.effectiveRoles` |

`RoleService.effectiveRoles` carries both `.1` and `.3` because it is the one
method that both unions the three sources (`.1`'s concern) and decides which
source wins when a role is held by more than one (`.3`'s concern, since that
attribution is exactly what the identity endpoint later reports verbatim).

## Decision 2 — the parent SVC brackets validation-then-consumption, not a fourth guarantee

`GW_AUTH_0015`'s cross-cutting claim is that validation and consumption are
one continuous guarantee: a mapping is either caught at startup or, having
passed, is trustworthy at every point it is read. No single child states
both halves. `SVC_GW_AUTH_0015` is retargeted to bracket exactly that: the
four malformed-mapping cases from `ClaimRoleMapperTests
.a_malformed_mapping_refuses_construction` (validation refuses to run) and
`ClaimRoleMappingTests.a_mapped_admin_claim_grants_the_admin_surface_with_no_
grant_row` (a validated mapping grants a real capability and reports itself
on the identity endpoint, with no grant row behind it). Together they show
the whole arc: nothing downstream of a bad mapping runs at all, and
everything downstream of a good one is both real and visible.

`ClaimRoleMapper.validated` keeps the bare parent id alongside `.2` for the
same mechanical reason PR #324, #333, #334, and this branch's
`GW_VETTING_0029` change all found: reqstool resolves `@SVCs` to a JUnit
`Class.method`, so a production annotation site is still needed for the
parent id to count as implemented, and `validated` is the one method the
parent SVC's "never runs" half is actually about.

## Decision 3 — no new `@Requirements` annotations were needed

Unlike `GW_VETTING_0029` and `GW_APPROVAL_0004` on this branch, every
guarantee here already had a genuine implementation site carrying
`@Requirements({"GW_AUTH_0015"})` before this change — `validated`,
`rolesFrom`, `effectiveRoles`, and `MeController.me` were all already
annotated. The only new step this decomposition required was checking, after
moving every site to a child, that the bare parent id still had at least one
real annotation left (it did not, until `validated` was given it back
alongside `.2`) — the same check this branch's two prior fix-up commits
(`GW_RELEASE_0003.5`'s missing `implementation: configuration`,
`GW_VETTING_0029`'s missing bare-parent annotation) established as
mandatory before running the gates, rather than after.

## Risks

- **Merge conflicts.** Both `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` grow ids near where other in-flight work
  on this same branch appends; resolved by ordering edits sequentially and
  running the independent id check after each.
- **No new top-level id was minted.** This change consumes no `GW_AUTH_NNNN`
  number and cannot collide with a reserved range.
