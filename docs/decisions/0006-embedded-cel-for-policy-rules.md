# ADR 0006 — Embedded CEL for policy rules, not an OPA sidecar

*Accepted, 2026-08-18.*

## Context

The architecture document sketched the policy engine as "policy-as-code
(OPA-style) consuming the normalized connector verdicts" (§4). Issue #12 asked
for declarative rules over snapshot metadata and content inventory, a rule
playground, and provenance when a rule decides. The issue's assessment settled
the product scope: **deny rules only** — auto-approval contradicts the
product's first principle ("nothing is served that a person did not approve")
and stays parked until the delegated-approval question is decided
deliberately, together with the risk-tier machinery (§6).

The engine choice remained open: an OPA/Rego sidecar, or an embedded
expression evaluator.

## Decision

**Embedded [cel-java](https://github.com/google/cel-java) (`dev.cel:cel`).**
Policy rules are CEL expressions compiled — parsed and type-checked to a
boolean over a documented variable surface — at write time, and evaluated
fail-closed inside `ApprovalService` at decision time.

- **One binary stays one binary.** The gateway's deployment story (ADR 0002,
  GraalVM native release, Helm chart, GitOps estate) has no second process to
  deploy, secure, version and health-check. An OPA sidecar would put a network
  hop and its failure mode inside the approval gate.
- **CEL is the right blast radius for user-authored code.** Expressions are
  non-Turing-complete and terminating; the runtime registers no custom
  functions, so a rule can read the facts it is handed and nothing else — no
  I/O, no side effects. An explicit comprehension-iteration bound closes what
  nesting can still multiply. This is what makes the playground safe to point
  at real snapshots.
- **The trust model is unchanged.** Deny rules tighten the human gate; they
  never open it. Rule lifecycle is admin-only, audited, and declarable in the
  estate; a denial lands on the append-only ledger naming the rule.

## Consequences

- The §4 "OPA-style" sketch is amended: the engine is embedded CEL. The
  policy roadmap items (per-tier connector requirements, license allowlists,
  auto-approval conditions) would attach to this engine if and when they are
  decided — the expression language carries over.
- cel-java brings a protobuf runtime. The native-image release profile needs a
  reachability check for it before the next native release; the PR gates do
  not exercise the native profile.
- Waivers and policy denials stay different in kind on purpose: a waiver
  accepts one *finding* on one marketplace with an expiry; a policy denial has
  no per-snapshot override at all — the exception path is editing or disabling
  the rule, audited.
