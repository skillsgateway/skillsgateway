# Evidence: config-surface-guardrail

One fresh run of every gate after the last code edit.

Commit under test: `d59bf3838d5ed9126d2cf432764b0669d323333e`
Branch: `chore/config-surface-guardrail`

## Gates

### `MAVEN_OPTS="-Xmx3g" ./mvnw clean verify`

```
[INFO] Tests run: 636, Failures: 0, Errors: 0, Skipped: 9
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

### `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### `(cd src/main/frontend && pnpm e2e)`

```
  13 passed
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
216/216 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 32 passed, 0 failed (32 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 1.14 seconds
```

## The measurement

`target/config-surface.txt` lists **105** leaves. Cross-checked against
`docs/manual/reference/configuration.md`: every one of the 95 property names its
tables give in full is a real code leaf, and the 10 that are not in that set are
documented under an abbreviated prefix (the nine
`…external-sources.budgets.*`) or as a YAML block (`retention.marketplaces`).
Nothing is documented that does not exist, and nothing exists that is not
documented.

The assessment's hand count of 127 does not reproduce.

## Mutation results — every new guard fails against the thing it names

| Mutation | Test that failed |
| --- | --- |
| A component added to `SkillsGatewayProperties.Approval` | `ConfigSurfaceBudgetTests` — "now offers 106 configuration leaves; the budget is 105" |
| `RemovedPropertyGuard` stops consulting the environment | `RemovedPropertyGuardTests` ×5 |
| Every disposition refuses (`!= null` instead of `== REFUSE`) | `RemovedPropertyGuardTests.a_removal_that_only_costs_latency_is_ignored_rather_than_refused:111` |
| `RoleBootstrapGuard` drops its `RemovedPropertyGuard` parameter | `RemovedPropertyGuardTests.the_bootstrap_guard_is_constructed_after_this_one:94` |

**The last mutation initially survived, and the test was rewritten because of
it.** `the_removed_property_is_refused_even_when_there_is_also_no_administrator`
passed with the dependency removed — the container happened to build the guards
in the order that makes the assertion true. Its javadoc claimed it would catch
that, and it would not have. The behavioural case stays (it is the operator-facing
property) and a structural assertion on the constructor was added beside it,
which is what actually holds the wiring in place.

## What the gates do not cover

- **The ratchet counts leaves, not meaning.** It cannot tell a knob an operator
  needs from one added in case somebody wanted it, and it says nothing about
  whether a default is defensible. It makes growth visible; the judgement is
  still a reviewer's.
- **The leaf/documentation cross-check was done once, by hand, and is not a
  test.** A leaf added and undocumented would trip the ratchet, not a doc gate.
  Making that a gate means parsing the reference's tables, which is a bigger
  change than this one.
- **`WARN` has no production entry.** It is exercised on the mechanism with a
  synthetic map, so what is verified is that the disposition works — not that any
  real removal has been judged to deserve it.
- **The relaxed-binding claim rests on one spelling.** The environment-variable
  case goes through a real `SystemEnvironmentPropertySource`, which is the
  spelling a Helm chart or container runtime produces. The other forms Spring
  would resolve are the framework's contract and are not enumerated here.
- **Nothing asserts the guard runs before the application does anything.** Both
  guards are ordinary singletons; a component constructed earlier that reads a
  removed property would see it. None does today.
