# Proposal: the git endpoints speak smart HTTP only

## Why

Both `GitServlet`s, `/git/**` and `/publish/**`, rely on JGit's defaults, and
by default those also serve the "dumb" HTTP protocol: plain `GET`s of
`info/refs`, `HEAD` and `objects/...` straight out of the repository. Every
control the facade attaches lives on upload-pack, and the dumb routes never
construct one:
- the served-ref filter;
- the advertised-want policy;
- the fetch ledger hook.

Withdrawal removes refs, not objects. So `GW_FACADE_0002 — Only approved content
fetchable` and `GW_AUDIT_0001 — Fetch audit log` hold only for clients that
choose the smart protocol.

## What Changes

- Both servlets disable dumb HTTP. Every dumb route answers with a refusal
  instead of repository files.
- The smart protocol is unchanged. Every git client this project supports
  speaks it, and it remains the only route that delivers objects.
- No requirement text changes, since both requirements already say this. New
  `SVC_GW_FACADE_0036` verifies it.
- Not **BREAKING**: no API, schema or configuration change. A git client that
  only speaks dumb HTTP stops working, and none is supported.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `git-facade`: `GW_FACADE_0002 — Only approved content fetchable` gains
  `SVC_GW_FACADE_0036`.

## Impact

- `GitFacadeConfiguration.gitServlet`, `GitPublishConfiguration.publishServlet`.
- Docs: `concepts/trust-boundaries.md` (the facade section names smart HTTP as
  the only protocol).
