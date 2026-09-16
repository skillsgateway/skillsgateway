# Proposal: portal-user-menu

Implements [#395](https://github.com/skillsgateway/skillsgateway/issues/395).
Navigation and placement in the portal shell; no new server capability and no
API change — `GET /api/me` already returns the effective roles with the source
of each and the truncation flag.

## Why

Three things about the shell are in the wrong place, and all three cost the
same person the same thing: they cannot tell what the portal thinks they are.

1. **Who is signed in is a bare label.** The sidebar footer prints the username
   and nothing else. The session's effective roles — and, more usefully, *where
   each came from* (configuration, a stored grant, an identity-provider claim,
   or the development escape hatch) — are already on the wire from
   `GET /api/me` and are shown nowhere. A user refused a mutation has to read
   the deployment's configuration to find out why.
2. **The theme control sits alone in the top bar**, beside a breadcrumb it has
   nothing to do with. It is a per-user preference, and convention puts those
   with the person, not with the page.
3. **Personal access tokens are top-level navigation**, in a group named
   "Access" that holds exactly one item, beside marketplaces and the audit log
   — estate-wide concerns. A token is the one thing on the portal that is
   nobody's but yours: a user with no role can still mint one, and only ever
   sees their own (`GW_AUTH_0005 — Portal token self-service` is scoped per
   principal server-side).

## What Changes

- **A user menu in the top-right of the header.** Its trigger names the signed-in
  user; the popup carries the identity, the effective roles with the source of
  each, the theme cycle, a link to "Your tokens", and sign out.
- **Roles are shown with their source.** One row per effective role: the role,
  the marketplace an approver role is scoped to, and where it came from —
  `config`, `grant`, `claim` or `dev-insecure-auth`, rendered as words rather
  than the wire values. A session holding no role says so in one line, naming
  what it can still do (read the portal, and mint its own tokens) rather than
  showing an empty list. When the identity provider truncated the membership
  claim (`claimsTruncated`), the menu says the list may be incomplete instead of
  presenting it as the whole truth.
- **Sign out.** The portal has had no logout control at all — ending a session
  meant clearing the cookie by hand. The menu posts to Spring Security's own
  `/logout` with the CSRF token the portal already carries, then returns to `/`,
  which starts a fresh login. Under `skills-gateway.dev-insecure-auth=true`
  there is no session to end, and the menu says so instead of offering a control
  that does nothing.
- **The theme cycle moves into the menu.** Same three states in the same order
  (system → light → dark), same persistence, same accessible name; only its
  placement changes, and it gains a visible label now that it is not a lone icon
  in a bar.
- **Access tokens leaves top-level navigation.** The "Access" sidebar group goes
  away; `/tokens` stays a route, reached from the user menu ("Your tokens") and
  from the Overview card that already links to it. Bookmarks, deep links and
  every documentation cross-reference keep working.
- **No API change.** `MeView` (username, roles with their source,
  `claimsTruncated`) is already exactly what this needs. Nothing is added to it,
  and `openapi.json` is unchanged.

## Capabilities

### New Capabilities

**admin-portal.** Three requirements, continuing the `GW_AUTH_` sequence from
the current maximum `GW_AUTH_0043`. `GW_AUTH_0031`–`GW_AUTH_0039` remain the gap
the facade IdP-bearer change left deliberately for the concurrent
[#394](https://github.com/skillsgateway/skillsgateway/issues/394) work; this
change does not take from it.

- `GW_AUTH_0044` — Portal user menu with identity and effective roles
- `GW_AUTH_0045` — Portal sign-out ends the browser session
- `GW_AUTH_0046` — Personal access tokens are a per-user surface reached from the user menu

### Modified Capabilities

_None._ No existing requirement changes its meaning. `GW_AUTH_0005 — Portal
token self-service` still holds exactly as written: the page, the show-once
cleartext and the revocation are untouched — only how the page is reached
changes, and `GW_AUTH_0046` states that beside it. `GW_INGEST_0007 — Portal
marketplace and snapshot administration` is unaffected: the marketplace and
governance navigation is unchanged.

## The stop rule

This change adds no configuration leaf, no Spring context, no endpoint and no
database column. It is a net deletion in the navigation — one sidebar group
removed — in exchange for one component, and it makes an answer the server
already computes (`why do I hold this role?`) visible instead of requiring a
second system to read.

## Impact

- **Portal only.** `components/app-layout.tsx` (the shell, the user menu, the
  theme control), `main.tsx` (unchanged routes — `/tokens` stays), a Storybook
  story enumerating the personas (roles from every source, no role at all,
  truncated claims, the development hatch, and the failed `/api/me` read), the
  component test, and a Playwright step through the menu to the tokens page.
- **Server: none.** No controller, no security configuration, no migration.
  Sign-out uses the `/logout` endpoint Spring Security's defaults already
  register on the web chain; nothing is configured to enable it.
- **API contract: unchanged.** No path, field or type moves.
- **Docs.** `reference/portal.md` (navigation table, the user menu, where tokens
  live, the theme control, and the "there is no logout control" note it
  retires), plus any guide that sends the reader to "Access tokens in the
  sidebar". `DESIGN.md` records the shell as shipped.
