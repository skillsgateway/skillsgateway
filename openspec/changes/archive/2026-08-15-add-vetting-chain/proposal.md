# Proposal: add-vetting-chain

## Why

Ingestion records a SHA-pinned snapshot and holds it, and the only thing standing between
quarantine and the facade is a reviewer clicking Approve. The reviewer is shown *what* the
snapshot contains (`GET /api/snapshots/{id}/content`) but nothing about whether it is safe:
no scan ever runs, so a snapshot carrying an AWS key or a `SKILL.md` that tells the agent to
read `~/.aws/credentials` looks exactly like a clean one. ARCHITECTURE.md §4 describes the
gateway as an *orchestrator* of vetting connectors; today there is no orchestrator and no
connector.

Issue #10 asks for that orchestrator: an ordered chain of pluggable connectors that runs at
ingestion against the quarantined snapshot, records a verdict per (snapshot SHA, connector,
run), aggregates fail-closed, and shows the reviewer the verdicts and findings before the
decision. It is the foundation for waivers (#28) and continuous re-vetting (#24).

## What Changes

- **Connector SPI** (`VettingConnector`): a connector has a stable `name()`, an `order()`, and
  `vet(SnapshotUnderVetting)` returning a `Verdict`. The snapshot handed to a connector is
  read-only content access over the quarantined commit (path + bytes walk), the SHA, and the
  marketplace — never a repository handle, so a connector cannot mutate quarantine.
- **Verdict model**: `PASS`, `WARN`, `FAIL`, `ERROR`, `PENDING` with `Finding(id, severity,
  location, message)` and an optional report URL. `PENDING` exists as groundwork for the
  async/webhook connector that is out of v1 scope; the aggregator already treats it as
  blocking, so an async connector slots in without changing the gate.
- **Ordered chain with fail-closed aggregation** (`VettingChain`): every connector runs, in
  `order()`, and *all* of them run — the chain never short-circuits, because the reviewer
  wants the whole picture. A connector that throws, or that exceeds its timeout, is recorded
  as an `ERROR` verdict with the failure as a finding; it is never skipped. The chain outcome
  is `clear` only when every verdict is `PASS` or `WARN`; anything else (`FAIL`, `ERROR`,
  `PENDING`, or *no run at all*) is `blocked`.
- **v1 built-in connectors**:
  - `secret-scan` — regex + entropy rules over every text file in the snapshot (AWS access
    keys and secret keys, PEM private-key blocks, GitHub/Slack/Google tokens, JWTs, and
    assignment-shaped high-entropy tokens).
  - `prompt-injection` — pattern heuristics over Markdown instruction content (`SKILL.md`,
    commands, agents): instruction-override phrasing, system-prompt exfiltration, credential
    paths, review-process tampering, pipe-to-shell, and invisible/bidi Unicode. Explicitly
    documented as a heuristic triage signal with a high false-negative rate, not a semantic
    review.
- **Persistence** per run: `vetting_runs` (snapshot, trigger, outcome, timings),
  `vetting_verdicts` (one row per connector per run, with its chain position), and
  `vetting_findings`. Re-vetting (#24) adds runs with a different trigger; waivers (#28) hang
  off a finding's rule id and the snapshot.
- **Approval gate with a recorded override**: `POST /api/snapshots/{id}/approve` refuses a
  snapshot whose latest chain outcome is `blocked` with `409`, unless the request carries a
  non-blank `overrideReason`. The override is recorded on the run and in the append-only
  ledger with the reviewer's identity. This is deliberately the *minimum* — scoped,
  per-finding, expiring exceptions are #28, which will replace the blanket override.
- **Ledger**: the chain run outcome, each connector verdict, and every override land in the
  ledger. `fetch_log` gains a nullable `detail` column so a reason or a summary is a first
  class ledger field instead of being smuggled into `ref`.
- **Portal**: the marketplace detail page shows each snapshot's chain outcome, per-connector
  verdicts and findings; the marketplaces page requires a reason before approving a blocked
  snapshot.
- **Configuration**: `skills-gateway.vetting.timeout` and `skills-gateway.vetting.max-file-bytes`.
- New requirements GW_0037–GW_0043 with SVC_GW_0037–SVC_GW_0043.

Out of v1, recorded as follow-ons in `design.md`: malware scanning, the LLM semantic-review
connector (promptfoo, ARCHITECTURE.md §14.2), the external webhook/async connector, waivers
(#28), and continuous re-vetting (#24).

## Capabilities

### New Capabilities

- `snapshot-vetting`: the ordered connector chain at ingestion (GW_0037), fail-closed
  aggregation including connector crashes (GW_0038), the secret-scanning connector (GW_0039),
  the prompt-injection heuristic connector (GW_0040), the approval gate with a recorded
  reviewer override (GW_0041), the portal verdict surface (GW_0042), and the ledger record of
  runs, verdicts and overrides (GW_0043).

### Modified Capabilities

(none — no existing requirement text changes. `GW_0005` (approval) keeps its meaning: the
gate is an additional precondition on approving, not a change to what approval does, and
`GW_0004` (held until approved) is strengthened rather than altered.)

## Impact

- New: `vetting/` package (SPI, verdict model, chain, service, repository, two connectors,
  controller).
- Changed: `V1__init.sql` (three tables, one column on `fetch_log` — the repo keeps a single
  migration), `IngestionService` (runs the chain), `ApprovalService` + `AdminController`
  (the gate and the override), `AdminAuditLogger` / `FetchLogRepository` (`detail`),
  `SkillsGatewayProperties` (`vetting`), `WebhookEvent` (`snapshot.vetted`),
  `src/main/frontend/` (queries, marketplace detail, marketplaces page, MSW handlers,
  generated OpenAPI types), `docs/reqstool/*.yml`, and the documentation site.
- Trust-boundary change: approval is what publishes content, so the gate is adversarially
  tested — a connector that throws must leave the snapshot held and blocked, planted secrets
  and planted injection markers must be found, and an approval without a reason must be
  refused.
