# Proposal: read-only-forge-mirror

## Why

[ADR 0008](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0008-serving-surface-stays-the-embedded-facade.md)
settled that the embedded facade stays the canonical, audited, enforced serving
surface, and identified two follow-ups for the things a forge is genuinely
better at. The identity half shipped in
[#120](https://github.com/skillsgateway/skillsgateway/pull/120), archived as
`2026-08-23-session-git-credentials`.

The second half is visibility: approved content copied to a forge repository so
people can browse, search and read it with the tools they already have, while
every agent install and every CI fetch keeps pointing at the facade and staying
on the ledger. The ADR deliberately sequenced it after the identity work,
because it is an outbound integration with its own failure modes — partial
pushes, a mirror that drifts from what is served, a revocation that must reach
it — and because it must never become a surface people install from.

Issue [#59](https://github.com/skillsgateway/skillsgateway/issues/59) is triaged
Low-Medium and names its own first increment: **push-on-approve for one
marketplace behind a config flag, plus a drift-check admin endpoint, and no
auto-provisioning of forge repositories.** This change is exactly that, and the
triage is explicit that push is the easy part — the budget is spent on
revocation propagation, drift and failure modes.

## What Changes

- **An optional mirror, off by default (GW_FACADE_0020).** A new
  `skills-gateway.mirror` block names one marketplace, a clone URL and a
  credential. Nothing happens until an operator sets `enabled: true`, so an
  upgrade changes nothing. Only published content is ever pushed: the
  reconciliation reads published storage and no other repository, so quarantine
  and a hosted marketplace's origin cannot leak by any path. The mirror URL faces
  the same `skills-gateway.allowed-url-schemes` allowlist that governs
  registration — one policy for every address the gateway dereferences — and a
  URL that embeds a credential is refused so the URL stays safe to print.
- **Wired as an event, not a call (GW_FACADE_0021).** `ApprovalService` and
  `RevetService` raise a `ServedContentChangedEvent` after the transition they
  just made; a listener queues a reconciliation on the mirror's own single
  thread. The approving thread neither waits for the forge nor can be failed by
  it, and nothing in serving or authorization reads anything the mirror writes.
- **Reconciliation, not deltas (GW_FACADE_0022).** Each task pushes published storage's
  current served reference set and deletes whatever the mirror still holds inside
  those namespaces. Revocation therefore propagates without a second code path —
  a snapshot that is no longer served is a reference the next reconciliation
  deletes — and a lost or reordered task cannot corrupt the mirror, only delay
  it. A push that exhausts its retries is recorded on the ledger and left as
  drift; it never blocks or reverses the revocation that triggered it.
- **A drift report (GW_FACADE_0023).** `GET /api/mirror/drift`, administrator-only,
  compares the mirror's references against published storage now and names the
  ones missing, held at another commit, or left behind. A mirror the gateway
  cannot read is reported unreachable, never as agreeing.

## Capabilities

### New Capabilities

- `forge-mirror`: an optional, read-only copy of approved content on an external
  git host, maintained by reconciliation against what the facade serves, never
  consulted by serving or approval, and reportable when it diverges.

### Modified Capabilities

_None._ Approval and re-vetting gain an announcement of a transition they
already made; neither their outcome nor their contract changes.

## Out of scope (named, so the boundary is explicit)

Deferred to later increments of #59: auto-provisioning of the forge repository
(it is created by hand and the gateway never calls a forge API); more than one
mirrored marketplace; per-marketplace mirror configuration in
`skills-gateway.estate.*`; a durable retry queue surviving restart; automatic
reconciliation of drift the gateway did not cause (a scheduled sweep); drift on
`GatewayMetrics` and in the portal; README or repository-description injection
labelling the mirror read-only; and a machine-API scope reaching the drift
report.

## Impact

- **DB**: none. No migration, no schema change, no new table.
- **Backend**: new `dev.skillsgateway.server.mirror` package
  (`ForgeMirrorService`, `MirrorTarget`, `MirrorUrlPolicy`, `MirrorReport`,
  `MirrorController`, `MirrorPublicationListener`, `MirrorConfiguration`);
  `SkillsGatewayProperties` gains a `Mirror` record; `GitStorage` gains
  `isServedRef` and the two served-namespace constants, which
  `GitFacadeConfiguration` now uses instead of its private copies.
- **API**: additive — one new path, `GET /api/mirror/drift`, and one new schema.
  `src/main/frontend/openapi.json` and `src/api/types.gen.ts` regenerated. No
  existing path, DTO or field changes, so the breaking-change gate has nothing to
  fail on.
- **Trust boundary**: this hangs off `ApprovalService` and the revocation path
  and creates the gateway's second outbound network surface → adversarial and
  negative tests are part of the definition of done, and `evidence.md` ships with
  the change.
- **Docs** (same PR): `architecture.md`, a new
  `guides/read-only-forge-mirror.md`, `reference/configuration.md`,
  `reference/api/mirror.md`, `concepts/trust-boundaries.md`.
