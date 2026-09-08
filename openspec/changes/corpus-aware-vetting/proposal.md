# Proposal: corpus-aware-vetting

## Why

T5 — typosquatting and lookalikes — is the only row of the threat model in
`docs/manual/architecture.md` with no mitigation at all. Covering it needs a rule
that can ask a question of the **approved estate**, not only of the snapshot in
front of it, and the gateway has nowhere to ask one: plugin names exist only
inside git trees, and the only service that extracts them —
`SnapshotFactsService` — is called from `PolicyGate` at approval time and
discards its answer when the call returns.

`CatalogService.mergePlugin` is often cited as the existing collision check. It
is not the one you would want. Its key is the *marketplace-prefixed* name, so the
same plugin name in two marketplaces never collides at all; what it actually
catches is the prefix ambiguity (`zed`/`tools-pro` versus `zed-tools`/`pro`), it
resolves it as "first in marketplace-name order wins, the rest are dropped", and
it runs after approval, when the content is already published. Detecting a
collision post-approval is too late to gate on.

The hard part is not the matching. It is that a vetting chain run is today a pure
function of **(pinned content, chain identity)** — the two things `vetting_runs`
records — and that purity is the entire answer to `GW_VETTING_0012 — Continuous
re-vetting of approved snapshots`. Adding corpus state as a third input to a run
would make a changed verdict over unchanged content mean nothing in particular.

[ADR 0015 — Corpus questions are approval-gate preconditions, not vetting
connectors](../../../docs/decisions/0015-corpus-questions-are-approval-gate-preconditions.md)
weighs that and decides it. **This change implements ADR 0015's first slice and
is blocked on the ADR being accepted.**

Issue [#253](https://github.com/skillsgateway/skillsgateway/issues/253), split
out of [#153](https://github.com/skillsgateway/skillsgateway/issues/153).

## What Changes

- **Snapshot facts become persisted state** — `GW_0194 — Persisted snapshot
  facts`. What `SnapshotFactsService` computes from a pinned commit is written
  once per snapshot at ingestion into `snapshot_facts`, and a derived
  `snapshot_plugin_names` row per plugin carries the plugin name, its manifest
  location, and its normalised collision key. The facts are a pure function of
  the pinned commit, so they are written once and never rewritten. The mutable
  `snapshot.state` field is **not** persisted; it is re-injected at read time.
- **`PolicyGate` reads the stored facts** instead of rebuilding them per
  approval. Same CEL variable namespace, same values, one JGit walk fewer per
  approval. Also extends `GW_0194 — Persisted snapshot facts`.
- **A collision precondition on the approval gate** — `GW_0195 — Approval is
  refused on a normalised plugin-name collision with the approved estate`. In
  `ApprovalService.doApprove`, beside the minimum release age and before any
  state transition: a plugin name in the snapshot whose normalised key equals
  that of a plugin in an already-approved, non-deleted snapshot of a **different**
  marketplace refuses the approval with `409` and a problem document naming the
  incumbent. First-come-wins: the incumbent is never re-evaluated and this check
  can never withdraw served content.
- **Acceptance is the existing waiver** — `GW_0196 — A collision refusal is
  acceptable only by a scoped, expiring waiver`. `vetting_waivers.rule_id` is an
  opaque string and `WaiverService.create` already takes an arbitrary rule id, so
  no waiver machinery changes; the precondition gains a waiver consultation. The
  `GW_VETTING_0028 — Administrative override of a blocked vetting outcome` override does
  **not** lift it, exactly as it does not lift the policy, release-age or
  four-eyes gates.
- **The decision is on the ledger and in the portal** — `GW_0197 — Collision
  refusals and their acceptances are audit-logged and shown to the reviewer`.
- **The chain's purity becomes a stated invariant** — `GW_0198 — Corpus state is
  never an input to a vetting chain run`. Stated as a requirement rather than
  left to the shape of the code, so a later change that hands a connector an
  estate query is caught rather than reviewed as a reasonable-looking
  improvement — the same reason `GW_FACADE_0021 — The mirror is never an enforcement
  path` is written down.

**No connector's verdict changes, and `SnapshotUnderVetting` is untouched.**

### What the first rule matches, and what it does not

Normalise (Unicode NFKC, casefold, UTS #39 confusable skeleton, then collapse
`-`, `_`, `.` and whitespace), then compare the keys **exactly**. `Claude-Skills`,
`claude_skills`, `claudeskills` and `cIaude-skills` reduce to one key.

**No edit distance in this slice.** Its false-positive rate scales with the size
of the estate, so the control would get noisier exactly as it got more useful,
which is the shape that trains reviewers to waive on sight. The evidence that
would settle it — the fraction of names within Levenshtein 1 of another in a real
multi-thousand-plugin estate, and how many of those pairs a reviewer judges
distinct — does not exist here. Normalisation and confusables need no such
measurement: the pairs they match are the same name in any practical namespace.

## Capabilities

### New Capabilities

- `snapshot-facts`: what a snapshot's pinned commit says about itself — files,
  plugins, skills and their locations — recorded once at ingestion as queryable
  state, so a question can be asked of the estate rather than of one tree walk.

### Modified Capabilities

- `snapshot-approval`: the approval gate gains a precondition that consults the
  approved estate, evaluated at the approval request with the release-age gate
  and before any state transition, refusing a snapshot that would introduce a
  normalised plugin-name collision.
- `vetting-waivers`: the scoped, expiring waiver becomes the acceptance act for a
  gate precondition as well as for a chain-run finding; nothing about a waiver's
  shape, scope, expiry or lifecycle changes.
- `policy-rules`: `PolicyGate` evaluates CEL rules against the persisted facts
  rather than rebuilding them per approval.
- `snapshot-vetting`: the chain's inputs are fixed as (pinned content, chain
  identity) by a stated invariant.

## Impact

- **DB**: `V2__snapshot_facts.sql` — `snapshot_facts` (`snapshot_id` UNIQUE FK,
  `facts` JSONB, `builder_version`, `built_at`, `unavailable_reason`) and
  `snapshot_plugin_names` (`snapshot_id` FK, `plugin_name`, `location`,
  `normalized_key`, index on `normalized_key`). The first migration after
  `V1__init.sql`.
- **Backend**: `SnapshotFactsService` (state excluded from the built map, a
  persisting entry point), `SnapshotFactsRepository` (new), `NameNormalizer`
  (new), `CollisionGate` (new, `dev.skillsgateway.server.approval`),
  `ApprovalService.doApprove` (one gate call), `PolicyGate` (reads stored facts),
  `IngestionService` (builds and stores facts after closure resolution),
  `SkillsGatewayProperties` (a new `collision` component — **shared record, see
  Risks**).
- **API**: additive. `POST /api/snapshots/{id}/approve` gains a `409` problem
  document type for a collision refusal, carrying the offending name, its
  normalised key, and the incumbent marketplace and snapshot. No existing field
  changes. `src/main/frontend/openapi.json` regenerated.
- **Trust boundary**: **crossed.** `ApprovalService` is a named trust boundary
  and this adds a gate to it. The `.claude/skills/old-coder` discipline and
  adversarial tests apply — specifically the concurrent-approval race named in
  ADR 0015's consequences.
- **Frontend**: the snapshot review surface shows a collision refusal and offers
  the waiver flow that already exists for a finding.
- **Docs** (same PR): `concepts/vetting.md` (the precondition, beside the
  minimum-release-age section that states the same principle),
  `guides/approving-snapshots.md`, `guides/waiving-findings.md`,
  `reference/configuration.md`, `reference/api/snapshots.md`,
  `architecture.md` (T5 row).

## Deliberately not in this slice

- **A search index.** `snapshot_facts` is a gating input. The search and
  discovery half of #153 was closed as not-now for want of a corpus; a corpus
  existing is not by itself a reason to reopen it.
- **Edit distance**, for the reason above.
- **The marketplace-name half of T5** — a registration-time near-miss warning
  extending `GW_INGEST_0029 — Duplicate upstream URL is reported as a registration
  warning`. Endorsed by ADR 0015, cheaper, and independent of this change.
- **The `mergePlugin` shadowing primitive** — that a marketplace name sorting
  earlier can silently displace an incumbent plugin from the virtual catalog is a
  real T5 variant, described in ADR 0015's Context, and needs its own change.
- **Anything corpus-aware inside the vetting chain.** That is what ADR 0015
  decides against.
