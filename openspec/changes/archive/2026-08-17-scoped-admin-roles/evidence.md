# Evidence: scoped-admin-roles

One final fresh run of every gate after the last code edit. Commit SHA at the
bottom.

## Discipline notes (old-coder Tier 3 — authorization on the approval path is a trust boundary)

- Spec approval: not obtained per-spec (autonomous run under the owner's
  standing "continue implementing, stack PRs" authorization). The committed
  OpenSpec change (proposal/design/specs/tasks, commit 88532ac) is the spec
  the implementation was held to.
- Visible spec addition recorded in `design.md`: `GET /api/webhooks` (the
  subscriber listing) sat in neither of the design's read enumerations; it is
  classified auditor-or-admin, deny-by-default over the browsing default,
  because it exposes receiver target URLs.
- **Route-table completeness check**: the deny-by-default walk first asserts
  its hand-written route classification equals the set of all non-GET `/api`
  routes introspected from the running application's
  `RequestMappingHandlerMapping` — 24 mutation routes (21 role-gated, 3
  owner-scoped token routes). A mutation endpoint added later without a
  deliberate classification fails `SVC_GW_0068` rather than shipping open.
- **Trust-boundary mutants** (both killed, then restored — restore verified
  via `git diff` showing zero `MUTANT` markers):
  1. `RoleService.requireAdmin` short-circuited (`if (true) return;`) →
     3 tests failed (deny walk: 403→404 leak-through; auditor mutation walk:
     403→200; grants admin-only: 403→201).
  2. Approver marketplace comparison removed from `approves(...)` →
     `SVC_GW_0069` failed (cross-marketplace ingest 403→201).
- **No-existence-oracle property**: an unauthorized `require*` denies with the
  same 403 whether the addressed snapshot/waiver exists or not; only an admin
  falls through to the controller's 404.
- No existing SVC test was weakened. The disabled-default compatibility is
  both implicit (every pre-existing suite runs in the default context and
  passes unchanged) and explicit (`RolesDisabledTests`, including the
  grants-API-as-inert-staging-data behavior).
- Transient environment note: two `clean verify` runs failed on a Docker
  daemon hiccup (`localhost:2375 failed to respond` starting the Testcontainers
  PostgreSQL container) unrelated to the change; the final run below is clean.

## Gates

### `./mvnw clean verify`

```
[INFO] Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  46.094 s
```

### `(cd src/main/frontend && pnpm e2e)`

```
  8 passed (19.9s)
```

### `reqstool status local -p docs/reqstool`

```
71/71 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 18 passed, 0 failed (18 items)
```

### `mkdocs build --strict`

```
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.48 seconds
```

## Commit

Implementation commit: `a74ce73` on `feat/scoped-admin-roles`
(feat(roles): scoped admin roles with deny-by-default enforcement (#26)).
