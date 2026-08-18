# Evidence: cel-policy-rules

One final fresh run of every gate after the last code edit (commit SHA at the
bottom; edits after the gated code state are this report, the tasks checklist,
spec/proposal wording fixes, and the archive move — no source changes).

## Discipline notes (old-coder Tier 3 — ApprovalService is a trust boundary)

- Spec approval: not obtained per-spec (autonomous run under the owner's
  delegated implementation brief for issue #12, which fixed the design rails:
  deny-rules-only per the issue's assessment comment, cel-java as the
  evaluator, playground + provenance, estate obligation). The committed
  OpenSpec change (proposal/design/specs/tasks, commit 914b5f1) is the spec
  the implementation was held to.
- **RED observed before GREEN**:
  - Unit: with `CelPolicy`/`SkillFrontmatter` stubbed to
    `UnsupportedOperationException`, all 14 unit tests failed
    (`CelPolicyTests`: `Tests run: 7, Failures: 3, Errors: 4`;
    `SkillFrontmatterTests`: `Tests run: 7, Failures: 3, Errors: 4`).
  - Integration: before any endpoint or gate existed, all 8 integration
    tests failed on behavior (routes 404, approval succeeding despite a
    matching rule): `PolicyRuleApiTests 1/1`, `PolicyEnforcementTests 6/6`,
    `PolicyPlaygroundTests 1/1` — `Tests run: 8, Failures: 8`.
- **Trust-boundary mutants** (each applied alone with a `MUTANT` marker,
  killed, restored via `git checkout`; zero `MUTANT` markers and a clean
  `git status` verified before commit):
  1. `PolicyGate`: match result discarded (`if (false && matches(...))`) →
     4 `PolicyEnforcementTests` failures (matching rule, comprehension bomb,
     ledger provenance, erroring rule).
  2. `PolicyGate`: evaluation errors silently skipped (`continue` in the
     catch — the fail-open this feature exists to prevent) → 3 failures
     (erroring rule, comprehension bomb, ledger provenance).
  3. `ApprovalService`: `policyGate.enforce(...)` call removed → 5 of 6
     `PolicyEnforcementTests` failures.
  4. `SkillFrontmatter`: malformed YAML returns "no tools" instead of
     raising → `malformed_skill_frontmatter_denies_under_a_tools_rule` and
     `malformed_yaml_raises` failed.
  5. `PolicyRuleService`: write-time compilation skipped → the lifecycle
     test failed on its 422 assertions.
  6. `PolicyController`: playground mutates state
     (`snapshotRepository.decide(...)` injected) → the playground inertness
     test failed — the negative control proving the "provably inert"
     assertions can detect a violation.
- Adversarial coverage (all asserted, all denying/refusing): CEL syntax
  errors, non-boolean expressions, undeclared variables (422 at write time,
  answered errors in the playground); runtime evaluation errors
  (out-of-range index); a comprehension bomb (40 files × 4 nested `all()`
  ≈ 2.6M iterations) refused within a 30 s preemptive timeout by the
  100 000-iteration cap; malformed SKILL.md frontmatter denying under a
  tools rule (an attacker malforming frontmatter cannot switch a rule off);
  every refusal leaving the snapshot `held` with `publishedIfServing`
  empty and no `snapshot-approved` ledger entry.
- Boundary and role coverage: the deny-by-default walk (`SVC_GW_0068`)
  now classifies `POST/PUT/DELETE /api/policy/rules*` and
  `POST /api/policy/playground` as role-gated mutations and
  `GET /api/policy/rules` as a privileged read — the walk's route-table
  completeness assertion forced the classification and re-verifies it every
  run. A dedicated test proves the playground is approver-scoped through
  the bare snapshot id (foreign approver and auditor refused) and rule
  management admin-only.
- No existing SVC test was weakened; existing tests changed only additively
  (estate declaration extended with a declared policy rule, converged count
  5 → 6; `Estate` constructor call sites gained the new trailing parameter).
- Known limits (declared, not covered): the GraalVM native release profile
  is not a PR gate — cel-java's protobuf runtime needs a reachability check
  before the next native release (flagged in ADR 0006 and design.md);
  SKILL.md YAML resource bombs (aliases, nesting, code-point limits) rely on
  SnakeYAML 2.x `SafeConstructor` defaults, exercised only through the
  malformed-shape tests; playground evaluation errors may echo CEL runtime
  error strings (never file content) to callers who can already read the
  full snapshot through the preview API.

## Gates

### `./mvnw clean verify`

```
[INFO] Spotless.Java is keeping 142 files clean - 0 needs changes to be clean, 142 were already clean, 0 were skipped
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  53.366 s
```

Surefire aggregate (68 test classes): `total=117 failures=0 errors=0
skipped=0`. UI gate ran inside verify (fresh
`src/main/frontend/test-results/vitest-junit.xml`).

### `(cd src/main/frontend && pnpm e2e)`

Real jar + mock OIDC IdP (compose.e2e.yaml):

```
  11 passed (24.4s)
normalized classnames in .../src/main/frontend/test-results/playwright-junit.xml
```
(exit code 0)

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
90/90 complete · 0 incomplete · PASS
```

(GW_0089–GW_0092 all COMPLETE.)

### `openspec validate --all --strict`

```
Totals: 23 passed, 0 failed (23 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.54 seconds
```

## Source state

Commit SHA of the gated source state:
`a950d63d1d7d0ffad04a86e5c48b613198ad575b` (includes every source change and
the ADR-number comment fix in pom.xml; commits after it are this report's
result fill-in and the archive move — no source changes).
