# Proposal: the portal offers only what the session's roles permit

## Why

Signed in as a user with no role, an auditor, or an approver of one marketplace,
the portal shows controls and pages those users cannot use (#774). The server
refuses every one of them correctly; only the portal is wrong. A user with no
role sees Ingest, Delete, Reject and Waive, and *Register marketplace*; an
auditor sees Ingest; an approver of one marketplace sees Ingest on another. The
sidebar lists the ledger, adoption and integration pages to everyone, and those
pages answer a refused read with the server's raw error text, a download link to
a refusal, "No fetches recorded yet." and an Overview count that never resolves.
The user menu tells a session with no role it "can read the portal", and the
vetting chain page sends an approver to a per-marketplace chain read the server
refuses them. After a role is revoked, the open page keeps the old controls
until a reload.

`/vetting` already derives what to show from the session read
(`GET /api/v1/me`). This change applies the same rule to the rest of the portal.
The server's authorization is not changed.

## What Changes

- New `GW_AUTH_0064 — The portal offers only the controls the session's roles
  permit`: ingest, approve, reject, waive, revoke a waiver, re-vet, delete and
  restore a snapshot, and register a marketplace are shown only where the
  session holds the role the server requires for that marketplace. A held
  snapshot's decision row says which role deciding it needs, rather than
  vanishing.
- New `GW_AUTH_0065 — A page the session's roles cannot read states the role it
  needs`: the audit log, adoption, webhooks, audit sinks, a marketplace's
  activity and the vetting chain page share one "needs role X" state. No
  download link, empty-state text or pending count is shown for a refused read.
  The estate-wide navigation leaves out pages the session cannot read; their
  addresses still resolve, to that state.
- New `GW_AUTH_0066 — The portal re-reads the session's roles when the server
  refuses`: any API response of 403 re-reads `GET /api/v1/me`, so the controls
  and pages follow a revoked or granted role without a reload.
- Copy: the no-role user menu says what such a session can actually do
  (`GW_AUTH_0044 — Portal user menu with identity and effective roles`, wording
  only). The vetting chain refusal no longer points approvers at a marketplace
  chain read the server refuses them.
- Not **BREAKING**: UI only. No API, configuration or schema change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-portal`: adds `GW_AUTH_0064`, `GW_AUTH_0065` and `GW_AUTH_0066`.

## Impact

- Portal only (`src/main/frontend/src/`): a role helper beside `useIsAdmin`, a
  shared role-required component, the query client's error hooks, and the
  marketplace, snapshot, audit, adoption, integrations, overview, vetting and
  shell components that gate on it.
- The MSW default session in the unit tests becomes an administrator, so the
  existing component tests keep exercising every control; the no-role tests set
  their session explicitly.
- Docs: `docs/manual/` pages on roles and the portal.
- Stop rule: no new backend package, estate object, grantable role, sweep or
  configuration leaf. The change narrows what the portal shows.
