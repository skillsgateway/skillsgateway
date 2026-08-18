# Policy-as-expression: CEL deny rules for approval

GitHub issue #12.

## Why

The approval gate is a person plus the vetting chain's evidence. What the
gateway cannot express today is an *organizational prohibition*: "no skill
declaring shell tools is ever approved here", "nothing from this namespace".
Such rules live in reviewers' heads, so they are enforced unevenly and leave
no record when they decide. The issue asks for declarative rules over snapshot
metadata and content inventory, a playground to test expressions against real
snapshots before enforcing them, and provenance when a rule decides.

The issue's assessment comment settles the scope: CEL **auto-approval**
contradicts the product's first principle ("nothing is served that a person
did not approve") and is parked until the delegated-approval question is
decided deliberately. What ships now is the inversion it recommends —
**deny rules only**, which tighten the human gate instead of loosening it,
with the engine, the playground and the provenance recording carrying over
unchanged if auto-approval is ever decided.

## What Changes

- **CEL deny rules as managed estate objects.** A rule is a name, a
  description, a [CEL](https://cel.dev) expression, and an enabled flag,
  stored in the database and managed through a new admin-gated
  `/api/policy/rules` API. An expression is parsed and type-checked to
  boolean at write time — an expression that does not compile is never
  stored. Rule lifecycle lands on the append-only ledger.
- **A fail-closed policy gate at approval.** `ApprovalService.approve`
  evaluates every enabled rule over the snapshot's facts — metadata
  (marketplace, SHA, state), the file inventory, and the plugin/skill
  inventory including each skill's declared tools parsed from SKILL.md
  frontmatter — *at the moment of approval*, after the vetting gate and
  before the state transition. A rule that evaluates true denies. A rule
  that errors denies. Facts that cannot be built (malformed frontmatter,
  oversized tree) deny. A refused approval leaves the snapshot held and
  publishes nothing. Evaluation is bounded (comprehension iteration limits,
  inventory caps) so a hostile expression or hostile content cannot hang
  the gate.
- **Decisions recorded in provenance.** Every refusal appends one
  `policy-denied` ledger entry per deciding rule, naming the rule, the
  snapshot SHA, the acting reviewer, and whether the rule matched or
  errored. The 409 response names the deciding rules, so the refusal is
  actionable.
- **A read-only rule playground.** `POST /api/policy/playground` evaluates
  any expression against a real snapshot — held, approved, or revoked —
  and answers matched/error without persisting anything, appending nothing
  to the ledger, and changing no state. Testing a rule before enforcing it
  is the design center: a bad expression is discovered in the playground,
  not in a blocked release train.
- **Declarative estate**: `skills-gateway.estate.policy-rules` declares
  rules as configuration, reconciled additively and idempotently through
  the same validated, audited service path as the API (continuous
  obligation #65). No new role is introduced, so IdP group-to-role mapping
  compatibility (#66) is untouched.
- Out of scope (declared non-goals, see design.md): auto-approval and any
  rule action other than deny (parked per the issue assessment); ingest-time
  deny rules and scoped policy bindings with graduated actions (the issue's
  scope-addition comment — the expression engine is built so these attach
  later); per-marketplace rule scoping (expressions can test
  `snapshot.marketplace` today); portal UI for rules and playground (the
  REST API and docs are the v1 surface; the portal grows a page when the
  rule set warrants one); an OPA sidecar (ADR 0004 records why embedded
  cel-java).

## Capabilities

### New Capabilities

- `policy-rules`: CEL deny rules — managed lifecycle and declarative estate
  (GW_0089), the fail-closed approval gate (GW_0090), ledger provenance of
  decisions (GW_0091), and the read-only playground (GW_0092).

### Modified Capabilities

<!-- none: snapshot-approval (GW_0005/GW_0041) is unchanged — the policy
     gate is additive and runs after the vetting gate; declarative-estate
     (GW_0083–GW_0087) is unchanged — policy rules are a new declared kind
     going through the same reconciliation contract -->

## Impact

- **Schema**: new `policy_rules` table folded into `V1__init.sql`.
- **Backend**: new `policy` package (`PolicyRule`, `PolicyRuleRepository`,
  `CelPolicy` — pure compile/evaluate core, `SnapshotFacts` — inventory
  builder with SKILL.md frontmatter parsing, `PolicyRuleService`,
  `PolicyGate`, `PolicyController`, `PolicyDeniedException`);
  `ApprovalService` gains the policy gate; `SkillsGatewayProperties.Estate`
  gains `policyRules`; `EstateReconciler` gains the `policy-rule` kind.
- **Dependency**: `dev.cel:cel` (embedded CEL evaluator, ADR 0004). Its
  protobuf runtime needs a GraalVM reachability check on the native release
  path — flagged in design.md as a known limit; the native profile is not
  part of the PR gates.
- **API**: `POST/GET /api/policy/rules`, `PUT/DELETE
  /api/policy/rules/{name}`, `POST /api/policy/playground`; all classified
  in the deny-by-default role-enforcement walk. OpenAPI + TS types
  regenerate.
- **Trust boundary**: `ApprovalService` — old-coder Tier 3; adversarial
  tests (hostile expressions, hostile content, bypass and mutation
  attempts) mandatory.
- **Docs**: new `guides/policy-rules.md` and `reference/api/policy.md`;
  `reference/configuration.md` estate block; ADR 0004 + architecture.md
  policy-engine note; glossary.
- **Traceability**: GW_0089–GW_0092 + SVC_GW_0089–SVC_GW_0092.
