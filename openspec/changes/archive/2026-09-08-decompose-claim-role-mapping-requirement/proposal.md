# Proposal: decompose-claim-role-mapping-requirement

## Why

Issue [#299](https://github.com/skillsgateway/skillsgateway/issues/299) asks the
project to decompose one multi-behaviour requirement per change, following the
pattern PR #324 established for `GW_INGEST_0026`, PR #333 for `GW_INGEST_0030`,
PR #334 for `GW_INGEST_0024`, and this branch's own prior commits for
`GW_RELEASE_0003`, `GW_VETTING_0029` and `GW_APPROVAL_0004`. `GW_AUTH_0015` —
*Identity-provider claim to role mapping* — is the last of the five candidates
those changes left unevaluated, and this change does exactly that one
requirement and nothing else.

`GW_AUTH_0015`'s single description enumerated three distinct,
independently verifiable guarantees behind one id and one SVC:

1. claim-derived roles are matched exactly against the configured mapping
   table and compose with configured and granted roles without ever widening
   privilege;
2. a malformed claim mapping refuses gateway startup;
3. the session identity endpoint reports which of the three sources granted
   each effective role.

These have different failure modes and, in the code, different implementing
methods: `ClaimRoleMapper.validated` (called once, at construction) versus
`ClaimRoleMapper.rolesFrom` (called on every request) versus
`MeController.me` (the reporting endpoint). A regression in the startup
validation would not touch request-time matching at all, and vice versa.

## What Changes

**This change decomposes exactly one requirement and nothing else.**

- **`GW_AUTH_0015` keeps its id and gains three children**,
  `GW_AUTH_0015.1` through `GW_AUTH_0015.3`, each linked to it by
  `references.requirement_ids`.
- **The parent's own text becomes the cross-cutting claim that is true across
  the whole set and is not any single child's**: a claim mapping is validated
  once at startup and, once valid, is exact and reportable at every point it
  is consulted — a session never receives a role the mapping table does not
  name for a value its own token actually carries, and nothing derived from it
  is invisible to an auditor reading the session's own identity.
- **One parent SVC and three child SVCs.** `SVC_GW_AUTH_0015` is retargeted to
  bracket the claim end to end — a malformed mapping refuses startup, and a
  valid one lets a mapped session act and reports the source on the identity
  endpoint with no grant row behind it — rather than restating all three
  guarantees; `SVC_GW_AUTH_0015.1` through `.3` each verify one guarantee.
- **Every annotation moves in the same commit**: `ClaimRoleMapper.validated`
  (startup validation) and `.rolesFrom` (request-time matching) already
  carried `@Requirements({"GW_AUTH_0015"})` each and split to `.2` and `.1`
  respectively — `validated` also keeps the bare parent id alongside `.2`,
  since it is the one method the parent SVC's "a malformed mapping never
  runs" half directly exercises. `RoleService.effectiveRoles` and
  `MeController.me` split to `.1`/`.3` and `.3` respectively. Six test
  methods across three JUnit classes, one e2e Playwright test, and two
  frontend JSDoc `@Requirements` tags have their annotations redistributed
  one guarantee per child.

Observable behaviour of the gateway is **unchanged**. No production code path,
no configuration key, no API response and no portal page changes. What the
change buys is that the traceability report now names which of the three
guarantees regressed, rather than reporting one verdict over all of them.

## Capabilities

### Modified Capabilities

- `admin-roles`: `GW_AUTH_0015` — *A claim mapping is validated before it can
  grant anything, and never grants more than it says* — is now a parent with
  three children. No behaviour delta.

## Out of scope

- **No sweep, no tooling.** Nothing enforces the decomposition convention
  automatically.
- This change completes the five candidates issue #299 left after PR #324
  established the pattern; it does not itself close #299 (that is done in the
  PR body once all five have a verdict, decomposed or judged monolithic).
