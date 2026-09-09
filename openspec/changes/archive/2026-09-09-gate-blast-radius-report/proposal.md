# Proposal: gate-blast-radius-report

## Why

`GET /api/snapshots/{id}/fetchers` returns the blast-radius report — the named
identities that received a snapshot's content through the facade, with fetch
counts and last-fetch times. It carried no role guard, and
`RoleEnforcementTests` pinned that open behaviour in place by asserting a 200
for a session holding no role at all. So the classification was deliberate,
not an oversight.

It is the wrong classification, and the repository already contains the
argument against it. The same facts reached through `/api/audit` require the
auditor role under GW_AUTH_0012 — Auditor read-only guarantee, and
`docs/manual/reference/api/audit.md` explicitly says `/fetchers` answers the
audit question. Two doors onto the same information, one gated and one not.

It was also the only identity-naming read on the open browsing surface. The
rest of that surface — marketplaces, catalog, snapshot contents, provenance,
vetting results, waiver lists — is about *content*. This one is about *people*,
and it is read from the same append-only fetch ledger that GW_AUTH_0010 —
Scoped admin roles with deny-by-default enforcement already refuses to a
no-role session everywhere else.

## What Changes

- **The report is gated to the approver of the snapshot's own marketplace, or
  an administrator** — `requireApproverOfSnapshot`, the same guard the
  `POST /api/snapshots/{id}/revet` handler beside it already uses. The person
  who can act on a revocation is the person who can see its blast radius.
- **Not the auditor role**, though `/api/audit` parity was the finding's
  argument. Auditor-gating would break the portal's "Already fetched by" panel
  for the marketplace approver working an incident on their own snapshot, and
  approver scoping is the tighter answer: an approver sees the blast radius of
  their own content and nobody else's. Auditors keep the same facts through
  `/api/audit`, which is unchanged.
- **The test that pinned it open now pins it shut**, in both directions: the
  no-role walk asserts 403, and the approver-scoping walk asserts 200 on the
  marketplace the approver holds and 403 on the one it does not.
- Machine credentials reach the route under scope `snapshots:read`; reach is
  the intersection of scope and role, so the guard narrows machine access with
  no registry change.

## Capabilities

### New Capabilities

_None._ Nothing is added. One route moves from the open browsing surface to
the approver-scoped set it always belonged in.

### Modified Capabilities

**admin-roles.** GW_AUTH_0011 — Per-marketplace approver scoping gains the
blast-radius report alongside the ingestion, approval, rejection, re-vetting
and waiver operations it already scopes, and SVC_GW_AUTH_0011 gains the
corresponding assertion. GW_AUTH_0010 — Scoped admin roles with deny-by-default
enforcement is unchanged: it already refuses every fetch-ledger read to a
session holding no applicable role, and this is one.

## Impact

**Breaking for one caller class.** A session with no role, or an auditor who is
not also an approver of that marketplace, previously received the report and now
receives 403. The API contract is additive within a major, and this narrows an
existing route rather than moving it — it is a security correction to a
misclassified read, taken deliberately under D1 of the 2026-09-08 architecture
assessment. No path prefix moves.

The portal is not changed. Its "Already fetched by" panel stays unconditional
and renders the API's refusal in its existing error branch, which is the
established convention for privileged reads in this portal — the same shape the
license pane and the adoption page already use.
