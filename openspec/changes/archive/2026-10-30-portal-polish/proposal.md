# Proposal: portal-polish

## Why

Exercising the portal against real marketplaces and a hostile fixture turned up a set of small
gaps ([#776](https://github.com/skillsgateway/skillsgateway/issues/776)): a browser opening an
address the gateway does not serve reads raw JSON or Tomcat's default error page, the sign-in and
sign-out pages are Spring's unbranded defaults and one of them tells anyone "You have been signed
out", the address keeps a `?continue` marker after sign-in, an unknown snapshot in the address is
silently ignored, every tab is titled "Skills Gateway", long unbroken text breaks three layouts,
vetting step names are truncated, the tokens page has no lifetime choice and no expiry, the review
queue says nothing about how long work has waited, and three accessibility gaps remain.

None of these needs new API or a new capability area; each is a narrowing of what an existing
surface says.

## What Changes

- **Browser error pages.** A browser page request for an address the gateway does not serve gets
  the portal, answered `404`, and the portal says "page not found" inside its shell. A request
  Tomcat cannot parse (`/%zz`) gets a minimal page in the portal's style when the client accepts
  HTML, plain text otherwise. Every other client, and every path under `/api`, `/git`, `/publish`,
  `/status`, `/hooks` and `/actuator`, keeps the format it has today.
- **Sign-in and sign-out pages.** `/login` and `/logout` are the gateway's own minimal pages: they
  say whether the reader is signed in and as whom, offer sign-in to a reader who is not, and offer
  sign-out (a form posting to the unchanged `/logout`) only to a session that has one to end. The
  sign-in flow and the menu's sign-out (GW_AUTH_0045 — Portal sign-out ends the browser session)
  are unchanged.
- **Address and title.** Each page's tab is titled `<Page> · <marketplace> · Skills Gateway` (the
  marketplace where the page has one); the `continue` marker sign-in leaves is removed from the
  address once the page loads; a `?snapshot=` naming no snapshot of this marketplace says so.
- **Layout.** Long unbroken plugin descriptions wrap inside their card, audit principals are
  truncated with the whole value on hover, Adoption breaks skill paths at their slashes, and
  vetting step names are shown whole.
- **Tokens page.** The same lifetime choice as the client wizard (7, 30, 90 days, no expiry;
  30 by default) and an Expires column that says "never" and marks an expired token.
- **Review queue.** Waiting time, the finding count of each snapshot's vetting run, and sorting by
  waiting time or marketplace — all from data the page already fetches (GW_INGEST_0037 — Review
  queue across marketplaces, modified).
- **Accessibility.** A skip link to the main content, `aria-sort` on sortable column headers, and
  accessible names on the webhook event checkboxes.

### Parked

- **`/tokens` in the sidebar.** GW_AUTH_0046 — Personal access tokens are a per-user surface
  reached from the user menu deliberately moved the entry out of the estate-wide navigation, and
  SVC_GW_AUTH_0046's real-browser test asserts its absence. The issue's premise that the page is
  reachable "only from Overview and the connect wizard" missed the user menu's "Your tokens". Adding
  the entry would reverse a recorded decision and weaken an SVC test, so it is not done here.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-portal`: adds GW_AUTH_0067 — A browser request the gateway cannot serve is answered with
  a portal page, GW_AUTH_0068 — The sign-in and sign-out pages state only what is true of the
  session reading them, GW_AUTH_0069 — The portal's address and tab title identify what it shows,
  GW_AUTH_0070 — Personal access tokens are created with a chosen lifetime and listed with their
  expiry; modifies GW_INGEST_0037 — Review queue across marketplaces.

## Impact

- Backend: `SecurityConfig` (the generated login/logout pages are replaced; `GET /login` and
  `GET /logout` are permitted), a sign-in pages controller, a browser not-found handler, a Tomcat
  error-report valve, one shared minimal-page renderer. No API, schema or configuration change.
- Portal: app shell (skip link, titles, `continue`), router (not-found route), review, snapshots,
  tokens, review queue, audit, marketplaces, adoption, webhooks, inventory, vetting flow.
- Docs: `reference/portal.md`, and the guides that show the affected pages.
