# Evidence: portal-mutable-token-scopes

One final fresh run of every gate after the last code edit, on commit
`01371d53` (`test(auth): classify the two scope-change routes in the deny-by-default walk`,
on top of `68d34428`, `feat(portal): let a token's scopes be edited after creation`; the only later
changes are this file, the task checkboxes and the archive). Run 2026-11-17, in the change's own
worktree. Stacked on `portal-pat-scopes` (#893) at `8fd5c921`, unchanged throughout.

Tier: 3 of `.claude/skills/old-coder` (token authority). Spec approval: not obtained (autonomous
run); the spec (`proposal.md`, `design.md`, the delta specs and the reqstool cases) is what the owner
reviews after the fact.

## Tests written first, and watched failing for the right reason

- All seven `TokenScopeEditTests` failed on the first run with the routes absent
  (`expected: 404 / 200 / 400` against the router's refusal of an unmapped `PATCH`).
- The route-classification guards fired on the new routes: `MachineApiScopeTests`' walk of the
  unreachable list threw on the `PATCH` verb it had no request builder for (second run), and
  `RoleEnforcementTests`' deny-by-default walk of every mutating route failed in the first full
  `./mvnw clean verify` on the two unclassified `PATCH` routes. Both were extended to name the
  routes and the verb; no assertion was loosened. `MachineApiRegistryTests` was green from its first
  run because the registry entries and the routes were added in the same step, so it was **not**
  watched failing for this change; its role here is regression armour.
- Strict bodies: with `@JsonIgnoreProperties(ignoreUnknown = false)` the extra field was **ignored**
  (`expected: 400 but was: 200`; that annotation only means "no override" under this gateway's mapper),
  so the design's fallback (D3) was taken: an any-setter refuses it. The same run showed the ledger
  holding an entry for the edit that should have been refused, which the fix removed.
- The race (`an_edit_racing_a_rotation_is_never_lost_from_the_successor`) failed against the unfixed
  rotation, on the first iteration, for the reason it exists for:

  ```
  [iteration 0: an edit reported saved must be in the successor]
  Expecting actual: ["mk9luk1nzam34"]  (the scope the token had before the edit)
  to contain exactly: ["mk9lulao4d636"] (the scope the edit reported saved)
  ```

  and passes (400 iterations, with edits that won the race asserted to have occurred) once rotation
  builds the successor from the row its revocation returns.
- The nine portal tests (`tokens-edit.test.tsx`, `system-tokens-edit.test.tsx`) failed with
  `Unable to find role="button" and name "Edit reach of token …"` before the control existed.

## Manual mutants (the tool-based layer is not available for Java here)

Each applied, the suite run, the file restored from a byte copy; a survivor would be a hole.

| Mutant | Result |
| --- | --- |
| M1 the personal lock also reaches machine credentials (`api_scopes IS NULL` dropped) | killed |
| M2 an edit applies to a revoked token (`revoked_at IS NULL` dropped from the lock) | killed |
| M3 the machine edit skips `validateApiScopes` | killed |
| M4 the ledger records the after list as the before | killed |
| M5 the machine entry is written under another event name | killed |
| F1 "every marketplace" is sent as the chosen names, not the explicit empty list | killed |
| F2 a widening is never warned about | killed |
| F3 an unchanged choice can be saved | killed |
| F4 the personal dialog can save a limited choice naming nothing | killed |
| F5 the system dialog can save with no scope | killed |
| F6 the edit control is offered on a revoked token | killed |

Not run: property-based tests (no parsing or serialisation invariant is added; the list functions
have exact-value unit tests) and a coverage tool (the project has none for Java; the changed lines
are each exercised by a test above). The independent-verifier option is not used.

## `./mvnw clean verify`

```
[INFO] Test Files  46 passed (46)
[INFO]       Tests  345 passed (345)
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.TokenScopeEditTests
[INFO] Tests run: 1379, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
exit 0
```

(1372 before this change, plus the seven in `TokenScopeEditTests`.) `OpenApiContractTests` is in this
run and green: the committed `openapi.json` is the served document, and its diff against the parent
branch has no removed line, so the breaking-change gate sees an additive change (the overloaded
`revoke` operations pin their published ids, design D12).

## `pnpm test:stories` (in `src/main/frontend`)

```
 Test Files  29 passed (29)
      Tests  152 passed (152)
```

(The first story run after adding stories failed three unrelated files with Vitest's "failed to find
the current suite", a dependency re-optimisation on a cold cache; the rerun and the run above passed.)

## `E2E_GATEWAY_PORT=18082 pnpm e2e`

```
  ✓  31 [chromium] › e2e/portal.spec.ts:1394:1 › snapshot_contents_are_explored_on_an_address_that_restores_the_same_file (8.6s)
  ✓  34 [chromium] › e2e/portal.spec.ts:1623:1 › a_chain_change_marks_held_evidence_superseded_and_the_reviewer_refreshes_it (2.7s)

  34 passed (2.4m)
normalized classnames in src/main/frontend/test-results/playwright-junit.xml
exit 0
```

The new test, `a_narrowed_token_is_refused_for_the_marketplace_it_no_longer_names`, is among the 34:
a token made on the personal tokens page fetches two marketplaces, is narrowed from the list with the
same secret and is then answered `not found` for the one it no longer names, and widened back.

## `reqstool status local -p docs/reqstool` (after the clean build and the e2e run)

```
INCOMPLETE (0)
423/423 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
✓ spec/virtual-catalog
✓ change/virtual-catalogs
Totals: 31 passed, 0 failed (31 items)
```

## `mkdocs build --strict`

```
$ NO_MKDOCS_2_WARNING=1 mkdocs build --strict
INFO    -  Documentation built in 1.12 seconds
```

`pnpm verify` (typecheck, oxlint, 345 jsdom tests, reqstool tags and merge) ran inside the build and
passed; the oxlint warnings it prints pre-date this change and are in other files. The Impeccable
detector reported nothing on the five portal files.

## Setup and environment

No dependency was added. No Flyway migration was needed (design D9). The gates ran in the change's own
worktree, whose `node_modules` and `target` are its own; the Maven and e2e runs shared one Podman VM
with another agent through a lock directory, so none ran concurrently with another.
