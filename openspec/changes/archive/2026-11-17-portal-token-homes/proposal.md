# Proposal: one home for each kind of token, and a status view for users

Issue: skillsgateway/skillsgateway#886.

## Why

Personal access tokens are reachable from two places in the portal (the user
menu and the Overview card), while machine API credentials have no portal
screen at all: the first one is minted by calling `POST /api/v1/tokens/machine`
by hand from a browser session. Separately, a user whose client has not picked
up a plugin's newest commit has nowhere to learn why (waiting for approval,
rejected, revoked, ingest failed); the pieces exist but are framed for
approvers.

## What Changes

- **Personal tokens, one entry point.** The user menu's single "Your tokens"
  link becomes a **Security** entry opening a Security section whose one page is
  **Personal access tokens** (today's `/tokens` content, unchanged). The Overview
  *Access tokens* card is removed. `/tokens` redirects to the new address.
  Visible to every signed-in user.
- **System access tokens**, a new page under *Configuration*, administrators
  only, the portal screen for the machine API credential endpoints: list,
  create (scopes from the real scope list, no "all", a lifetime chosen from
  30 days, 90 days, 180 days, 1 year or never, none preselected), rotate and revoke with confirmation, cleartext shown
  once with copy control and warning. Credentials close to expiry or never used
  are marked. The two pages state which kind they manage and link to each other.
- **Lifetimes (owner decision).** System access tokens offer 30 days, 90 days,
  180 days, 1 year or **never**; personal access tokens offer 30 days, 90 days,
  180 days or 1 year, default 30 days, and no never (the shared list today also
  offers 7 days and "No expiry"; both go). The API follows for machine
  credentials, **reversing part of `GW_AUTH_0024`**: the built-in cap moves from
  90 days to 1 year, and a credential may never expire, but only when the request
  says so explicitly (omitting an expiry is still refused) and only while the
  deployment configures no `skills-gateway.tokens.max-ttl`; a configured cap
  refuses never and wins over the built-in. The schema's `machine_credentials_expire`
  check is dropped by a new `V2__` migration (there are users now, so `V1` is
  never edited).
- **The UI term is "system access token"** in the page, the navigation and the
  docs; "machine API credential" stays the API's term and is mapped once on the
  page and in the token reference.
- **A marketplace status view**, readable by any signed-in user, built from
  `GET /api/v1/marketplaces` as it is today: what is served (commit, since when,
  approved by whom or automatically), whether something newer waits and in what
  state, who can decide it (the role, never named people), and why nothing is
  served. It adds no read of a snapshot's contents.
- **One new read-only endpoint:** `GET /api/v1/tokens/machine/scopes` (admin
  only, under the already machine-unreachable `/api/v1/tokens/**`), so the
  create form offers exactly the scopes the registry defines rather than a list
  copied into the frontend. The create endpoint changes only as the lifetimes bullet
  says; nobody's right to call any token endpoint changes.
- **What a role-less user sees is unchanged:** Overview, Review queue,
  Marketplaces (with *Connect a client*), the status view and their own tokens.
  Only the new Configuration page is added, for administrators.
- Documentation (`portal.md` navigation, user menu, access tokens;
  `api/tokens.md`) is updated in the same PR, replacing the note that the first
  credential must be minted by driving the API.

Reqstool: `GW_AUTH_0071` — Personal access tokens have one home, the Security
section of the user menu; `GW_AUTH_0072` — System access tokens are managed by
administrators from a Configuration page; `GW_AUTH_0073` — A system access
token's cleartext is shown once and kept nowhere; `GW_AUTH_0074` — System
access tokens close to expiry or never used are marked; `GW_AUTH_0075` — Any
signed-in user can see why a marketplace is not serving the newest commit;
`GW_AUTH_0076` — An administrator can read the API scopes a system access token
may hold; `GW_AUTH_0077` — A machine credential may be given no expiry only by
an explicit choice, and its built-in cap is one year; `GW_AUTH_0078` — Personal
access tokens are offered 30 days, 90 days, 180 days or one year. `GW_AUTH_0024`
— A machine credential states its lifetime and is administered centrally is
amended.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-portal`: the token navigation, the System access tokens page, the
  marketplace status view.
- `token-lifecycle`: the read-only scope list for administrators; machine
  credential lifetime (explicit never, one-year built-in cap).

## Stop rule

No backend package, estate object type, grantable role, sweep or configuration
leaf is added. Machine credentials are API-managed runtime state already (they
are not declarable in the estate, like PATs), so this adds no estate
obligation; design D7 says so. The one new route is justified in D3.

## Impact

Frontend: `app-layout.tsx`, `main.tsx`, `overview.tsx`, `tokens.tsx`, new
`system-tokens.tsx` and `marketplace-status` component. Backend:
`MachineTokenController` (one `GET`). Docs: `portal.md`, `api/tokens.md`.
Portal change: the PR carries before/after screenshots.
