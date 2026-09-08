# Evidence: skill-conformance-connector

Tier 1 (no trust boundary crossed). The connector reads paths and bytes of one
pinned commit through the same `SnapshotUnderVetting` seam every built-in uses,
and can only ever add findings; nothing on the approval path, the facade or the
ledger reads anything new. Two edges do carry risk and are treated as such: a
YAML parser pointed at attacker-supplied frontmatter, and a change to the
frontmatter parser the **policy gate** already depends on. Both are covered
below.

Implementation commit: `38ea4277ef8328d291cfd0095defa0209c71337d`.

## Spec ↔ test mapping

| Requirement | Verifies | Test |
| --- | --- | --- |
| GW_INGEST_0028 — SKILL.md conformance against a pinned Agent Skills specification | conformant skill passes; every required field missing or malformed is its own finding; unknown fields informational; no skills at all handled; hostile YAML refused as a finding; posture decides blocking; the pin is in the recorded chain identity | `SkillConformanceConnectorTests` (SVC_GW_INGEST_0028), 15 tests |
| GW_INGEST_0028 | the connector in the real chain: advisory verdict does not block, findings are deterministic across runs, the run's chain identity names the pin | `SkillConformanceTests` (SVC_GW_INGEST_0028), 3 tests |
| GW_INGEST_0028 | under `enforce`, the same defect blocks approval and is cleared only by an ordinary scoped, expiring waiver | `SkillConformanceEnforceTests` (SVC_GW_INGEST_0028), 2 tests |

## The specification is pinned, and the pin is stated

`src/main/resources/vetting/agentskills-2026-08-04.json` is a hand transcription
of the frontmatter table and per-field rules at
<https://agentskills.io/specification>, fetched once during development. Its
provenance block and `src/main/resources/vetting/README.md` record the upstream
source file `docs/specification.mdx` at commit
`217be548739f21d6008915c29aefe320ea1a90af` (2026-08-04), the reference validator
`skills-ref/src/skills_ref/validator.py` at commit
`547831f3a23724ba64a9b79bbf59c5e0bc8f2d1a` it was cross-checked against, and the
date pinned (2026-09-06).

**What is provisional.** Upstream publishes no version identifier, no tags, no
changelog and no machine-readable schema, so the "version" recorded is this
repository's dated pin rather than one upstream would recognise, and the
transcription is hand-made. Two rules deliberately follow the reference
validator rather than the prose, and both are written down in the README:
`allowed-tools` is accepted as a string *or* a list (the validator checks
nothing there, and lists are widespread), and an undefined top-level field is
reported informationally rather than as an error.

Nothing is fetched at vet time. `SkillSpec` reads the classpath resource in its
constructor and the connector holds it for the life of the bean; there is no
HTTP client on this path.

## The policy gate did not move

`SkillFrontmatter.tools(...)` is now implemented on top of the new non-throwing
`parse`, which is the only shared-code risk in the change. The evidence that its
contract is unchanged is that its own tests are unchanged and pass:

```
$ ./mvnw clean verify   (surefire, extract)
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- dev.skillsgateway.server.policy.SkillFrontmatterTests
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- dev.skillsgateway.server.policy.CelPolicyTests
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- dev.skillsgateway.server.PolicyEnforcementTests
```

Those seven pin every branch of the contract — absent frontmatter, frontmatter
without `allowed-tools`, a YAML list, a comma-separated string with parentheses,
malformed YAML, an unterminated block, and a non-scalar tools value — and each
still raises `PolicyEvaluationException` where it did, and still returns
`List.of()` where it did. None was modified, weakened or deleted.

The one loader limit that is not simply a restatement of SnakeYAML's default is
`codePointLimit`, lowered from 3 MiB to 1 MiB. It cannot change the policy
gate's behaviour: `SnapshotFactsService` refuses a `SKILL.md` over 256 KiB
*before* parsing it, so no document the policy path can reach is within a factor
of four of the new bound.

## Adversarial YAML: the limits refuse, they are not assumed to

Three hostile documents are driven through the connector, each inside
`assertTimeoutPreemptively(Duration.ofSeconds(10), …)` so a hang is a failure
rather than a stalled suite, and each asserted to produce exactly one
`skill-frontmatter-malformed` finding — **whose message names the limit that
refused it**, so the test cannot pass because something incidental happened to
fail:

| Input | Asserted message fragment |
| --- | --- |
| Alias bomb: 8 levels of 10 aliases each (70 aliases) | `aliases` — SnakeYAML's "Number of aliases for non-scalar nodes exceeds the specified max=50" |
| 200-deep nested flow sequences | `nesting` — "Nesting Depth exceeded max 50" |
| A 2 MiB frontmatter block | `exceeds the limit` — "The incoming YAML document exceeds the limit: 1048576 code points" |

`hostileFrontmatterIsAFindingAndNeverAnErroredConnector` re-runs the nesting
bomb through the **real chain** and asserts the recorded verdict is `WARN`, not
`ERROR`: a hostile document has to be an ordinary finding about the content, not
a crashed connector that blocks the snapshot for the wrong reason.

## The default posture is the safe one

`thePostureDecidesWhetherADefectBlocks` asserts both directions from one input:
`MEDIUM`/`WARN` by default, `HIGH`/`FAIL` under `enforce`.
`aNonConformantSkillWarnsWithAnActionableFindingAndDoesNotBlockApproval` then
approves a snapshot whose skill is malformed — the property an operator upgrading
into this connector actually depends on. `underEnforcementAConformanceDefect
BlocksApprovalUntilItIsWaived` is its counterpart: the same defect refuses
approval with the standard `VettingBlockedException`, names the connector and the
uncovered rule id, and is cleared only by an ordinary scoped, expiring waiver.

`anUnknownFieldIsInformationalUnderBothPostures` pins the one exception: a field
the pinned specification does not define never blocks, not even under
enforcement.

## Fixtures made honest rather than tests loosened

Two changes to existing tests, neither of which weakens an assertion:

- `AbstractGatewayTest.createUpstream` now writes a **conformant** `SKILL.md`,
  and so does the e2e upstream fixture. The shared fixture claimed to be a skill
  marketplace while shipping a `SKILL.md` with no frontmatter; a clean fixture
  has to mean a clean chain, or every test asserting one would be asserting
  around a standing finding.
- `ConnectorToggleTests.disabling_every_connector_leaves_a_run_blocked_not_clear`
  iterated a hardcoded list of three built-ins. It now iterates the real chain,
  so "every connector" stays literally true as connectors are added — a
  strictly stronger test than the one it replaces.

## Gates — one fresh run after the last edit

```
$ ./mvnw clean verify
[INFO] Spotless.Java is keeping 279 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 516, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  05:22 min
```

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

```
$ (cd src/main/frontend && pnpm e2e)
  13 passed (56.6s)
```

```
$ reqstool status local -p docs/reqstool
  GW_INGEST_0028             skills-gateway
156/156 complete · 0 incomplete · PASS
```

```
$ openspec validate --all --strict
✓ change/skill-conformance-connector
Totals: 31 passed, 0 failed (31 items)
```

```
$ mkdocs build --strict
INFO    -  Documentation built in 1.69 seconds
```

No flake reruns were needed: every gate passed first time on this tree. The two
known flakes — the `AuditExportTests` `ConcurrentModificationException` and the
Storybook/vitest runner wedge — did not occur.

The only failing run in this change's history was an earlier, deliberate one:
before the fixtures above were corrected, `./mvnw clean verify` failed on
`VettingTests.aCleanPassRecordsTheCoverageItExamined` (the new connector's
coverage summary did not use the word the GW_VETTING_0023 ledger assertion looks for) and
on `ConnectorToggleTests.disabling_every_connector_leaves_a_run_blocked_not_clear`
(a fourth connector was still running after the hardcoded three were switched
off). Both were fixed by changing the new code and generalising the toggle test,
never by relaxing an assertion — and they are useful evidence in their own right
that the existing GW_VETTING_0023 and GW_VETTING_0029 tests genuinely constrain a new connector.
