# Design: cel-policy-rules

## Context

The approval gate (`ApprovalService.doApprove`) already composes two checks
before the state transition: the state machine (`Snapshot.decidable()`) and
the fail-closed vetting gate (`WaiverService.evaluate` →
`VettingBlockedException`, GW_0041). The content inventory exists as
read-on-demand services over the quarantine object store
(`SnapshotContentService` for the manifest's plugins/skills,
`SnapshotPreviewService` for the file tree); nothing parses SKILL.md
frontmatter yet. `docs/manual/architecture.md` §4 sketches a policy engine
"OPA-style"; the issue's assessment comment recommends embedded cel-java
instead and deny-rules-only scope. Estate reconciliation (GW_0083–GW_0087)
defines the contract every new API-managed object kind must join.

## Goals / Non-Goals

**Goals:** organizational prohibitions as versioned, audited data — compiled
at write time, enforced fail-closed at approval time, recorded on the ledger
when they decide, testable against real snapshots before they are enforced,
declarable as configuration.

**Non-Goals:** auto-approval or any non-deny action (parked by the owner's
assessment until the delegated-approval question is decided — the engine,
playground and provenance carry over if it is); ingest-time deny rules and
scoped bindings with action ladders (issue's follow-up comment); portal UI;
OPA sidecar; per-marketplace rule scoping as a first-class field
(`snapshot.marketplace` in the expression covers it).

## Decisions

1. **Embedded cel-java, not an OPA sidecar (ADR 0006).** `dev.cel:cel` is a
   library: no new deployment unit, no network hop inside the approval path,
   native-image-friendlier than a sidecar contract. CEL is non-Turing-complete
   and terminating by design, which is exactly the right shape for an
   expression a hostile admin-adjacent user might author. *Rejected branch:*
   OPA/Rego sidecar (architecture.md's original sketch) — a second process to
   deploy, secure and health-check, and the gate would inherit a network
   failure mode; the sketch predates the estate-as-code direction where the
   gateway is one binary. Recorded as an ADR because it deviates from the
   architecture document. *Known limit:* cel-java carries a protobuf runtime;
   the GraalVM native release profile (not a PR gate) needs a reachability
   check before the next native release — flagged in evidence.md.

2. **A separate gate in `ApprovalService`, not a vetting connector.** The
   policy gate runs inside `doApprove`, after the vetting gate, before
   `snapshotRepository.decide`. *Rejected branch:* modeling rules as a
   `VettingConnector` — seductive (verdicts, findings, ledger and portal for
   free) but wrong twice over: (a) vetting runs are recorded at ingestion
   time and the effective outcome derives from the *recorded* run, so a rule
   created after a snapshot was vetted would not gate that snapshot — a
   policy that only applies to future ingestions is a hole, and the issue
   asks for evaluation at approval time; (b) connector findings are waivable
   by design, and a deny rule must not be — its exception path is editing or
   disabling the rule itself, audited, by an admin. The two gates stay
   different in kind: vetting is *evidence about content*, policy is *a
   standing organizational decision*.

3. **Fail closed, uniformly.** Anything that prevents a rule from producing
   `false` denies the approval: the expression evaluates true (matched), the
   expression errors at runtime (missing key, bad type), the facts cannot be
   built (unparseable SKILL.md frontmatter, file inventory over its cap), or
   the stored expression no longer compiles. Denials name every deciding rule
   at once (the vetting gate's "see everything wrong at once" principle).
   Write-time compilation makes the stored-expression-fails case nearly
   unreachable, but the gate still refuses rather than skips if it happens.
   *Rejected branch:* skip-and-warn on evaluation errors — an attacker who
   can provoke an evaluation error (e.g. malformed frontmatter that the
   facts builder chokes on) would thereby switch the rule off; that is the
   fail-open the whole feature exists to prevent. The cost is accepted
   explicitly: a broken rule blocks approvals until an admin fixes or
   disables it, and the refusal names the rule so the fix is one edit away.

4. **Compile at write time, evaluate at decision time.** `POST/PUT` on a
   rule parses and type-checks the expression against the declared variable
   environment with result type `bool`; failure is a 422 and nothing is
   stored. At approval, enabled rules are loaded and evaluated over freshly
   built facts. Evaluation is bounded: CEL comprehension iterations are
   capped, the file inventory is capped (over-cap is a facts failure, never
   a silent truncation — a truncated inventory would let content hide from
   a rule), and SKILL.md frontmatter parsing uses SnakeYAML's SafeConstructor
   with default resource limits. *Rejected branch:* caching compiled programs
   — premature; rule counts are tens, approvals are human-paced.

5. **Facts are a documented, versioned surface.** Variables: `snapshot`
   (map: id, sha, marketplace, state), `files` (list of {path, size}),
   `plugins` (list of {name, description, source}), `skills` (list of
   {name, path, plugin, tools}) where `tools` is the skill's declared tool
   list parsed from SKILL.md YAML frontmatter (`allowed-tools`, accepting
   both YAML list and comma-separated string forms; absent means empty
   list). Malformed frontmatter is a facts failure (decision 3): tools that
   cannot be read must not read as "no tools". The builder reads only the
   pinned commit's tree through JGit — same no-traversal property as
   preview/vetting; quarantine content never leaves the evaluation.

6. **Provenance is the ledger.** Each refusal appends one `policy-denied`
   entry per deciding rule (actor = the reviewer whose approval was refused,
   marketplace, SHA, detail `rule=<name> outcome=matched|error:<reason>`),
   written by `PolicyGate` before the exception propagates. Successful
   approvals record nothing new: a rule that did not match did not decide,
   and `snapshot-approved` already carries the decision that was made.
   *Rejected branch:* a `policy_decisions` table joined into
   `ApprovalService.provenance` — a second provenance store to keep
   consistent when the append-only ledger already answers "what decided,
   when, why" by design (see snapshots-and-ledger concept).

7. **Playground = the same compiler, the same facts builder, zero writes.**
   `POST /api/policy/playground {snapshotId, expression}` compiles, builds
   facts, evaluates, and answers `{matched, error}` — never echoing content,
   never persisting, never appending to the ledger (it is a read shaped as a
   POST, gated like a privileged mutation in the enforcement walk). It is
   gated `requireApproverOfSnapshot`: the caller is someone who could decide
   that snapshot and already sees its content through preview/content APIs.
   Evaluating against *real* snapshots rather than synthetic fixtures is the
   issue's explicit ask.

8. **Estate kind `policy-rule`, same contract as GW_0083.**
   `skills-gateway.estate.policy-rules[]` = {name, description, expression,
   enabled}; the reconciler creates or converges rules through
   `PolicyRuleService` (the exact API path), attributed to
   `config-reconciler`; a non-compiling declared expression is an isolated
   entry failure; absent declared rules are never deleted (additive-only);
   identical rules reconcile with zero writes. Rules are reconciled last —
   they reference nothing and nothing references them. No new role: admins
   manage rules, auditors+ read them, so #66 compatibility is untouched.

9. **Schema folded into `V1__init.sql`** (`policy_rules`: unique name,
   expression, enabled, created/updated attribution), per the standing
   pre-1.0 convention (the estate change did the same; Testcontainers
   recreate the schema every run).

## Old-coder discipline

Tier 3 (trust boundary: ApprovalService). Spec approval: not obtained
(autonomous run) — this design and the reqstool SSOT are the spec artifacts
the owner reviews in the PR. Failure model driving the test plan:

| Failure mode | Countermeasure (test) |
| --- | --- |
| Hostile expression: syntax/type error at write time | 422, nothing stored (SVC_GW_0089) |
| Hostile expression: runtime error at approval | deny naming the rule (SVC_GW_0090) |
| Hostile expression: comprehension bomb | iteration cap → error → deny, bounded time (SVC_GW_0090) |
| Hostile content: malformed frontmatter hides tools | facts failure → deny (SVC_GW_0090) |
| Fail-open drift: rule errors treated as pass | negative test asserts denial, snapshot stays held, facade unchanged (SVC_GW_0090) |
| Bypass: approval outside the gate | gate lives inside the only publisher (`ApprovalService`); test asserts refused approval publishes nothing (SVC_GW_0090) |
| Playground mutates or approves | test asserts identical DB/ledger/served state after playground calls, including error paths (SVC_GW_0092) |
| Denial invisible to audit | ledger entries asserted per deciding rule (SVC_GW_0091) |
| Unauthorized rule management | role-enforcement walk classifies every new route (SVC_GW_0089/0092) |
