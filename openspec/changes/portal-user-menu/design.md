## Context

See `proposal.md` — "Why". The shell today is `components/app-layout.tsx`: a
240px sidebar (brand, four navigation groups, a bare username pinned to the
bottom) and a top bar carrying an uppercase breadcrumb on the left and the
icon-only theme cycle on the right. `GET /api/me` already answers with
`{username, roles[{role, marketplace, source}], claimsTruncated}`; the portal
reads only `username` from it.

Constraints that shape the approach:

- **shadcn/ui on Base UI primitives only** (design-conventions). A menu is a
  focus-trapping, keyboard-navigable popup; hand-rolling one is how a shell
  loses its keyboard contract.
- **Every surface works in both themes and in `system`**, and Storybook runs axe
  with violations as errors.
- **The server is authoritative and the portal never hides controls by role**
  (`reference/portal.md`, "Authorization"). Showing roles must not turn into
  gating on them.
- The portal has no logout control at all today, and `reference/portal.md` says
  so in a note.

## Goals / Non-Goals

**Goals:**

- One place in the shell that answers "who am I, what am I, and how do I leave".
- Role provenance rendered as words, from the source values the API already
  sends.
- `/tokens` stays a route with an unchanged page; only its entry point moves.
- The no-role persona is verified, not assumed — including the empty and error
  states of the tokens page for that persona.

**Non-Goals:**

- No role-based hiding or disabling of any control. The menu *reports*; the
  server still refuses.
- No change to `MeView`, `/api/me`, `openapi.json`, or any server class. If the
  role source were missing from the API this change would stop rather than add
  it; it is present (`RoleService.EffectiveRole.source`).
- No "Your settings" page. The issue offers it as an alternative; a settings
  page with one control in it is a page nobody needs.
- No second theme control. The cycle moves; it is not duplicated.

## Decisions

### D1 — A real menu primitive, from the registry

`components/ui/menu.tsx` added with `pnpm dlx shadcn@latest add menu`, which for
this project's `base-nova` style resolves to Base UI's `Menu` — the same
primitive family as the existing `dialog.tsx`. Alternatives: a `<details>`
disclosure (no focus management, no arrow-key traversal, no escape-to-close) or
an always-open header panel (spends the top bar's whole width on chrome that is
read once a session). The primitive brings the keyboard contract the shell is
judged on: `Enter`/`Space`/`ArrowDown` to open, arrows to traverse, `Escape` to
close and restore focus to the trigger, `aria-haspopup`/`aria-expanded` on the
trigger — which `DESIGN.md` already has a rule for (popup triggers do not
translate on press).

### D2 — The trigger names the user; roles live inside

The trigger is the username with a small avatar-less identity glyph, not an
anonymous icon: the shell's job is to say who you are without a click. The roles,
their sources, the theme control, "Your tokens" and sign out are inside. A
trigger showing the roles too would put a variable-width, occasionally long
string in a fixed bar and would repeat in every screenshot of every page.

### D3 — Sources render as sentences, not wire values

`config` → "from configuration", `grant` → "granted in the portal", `claim` →
"from your identity provider", `dev-insecure-auth` → "development escape hatch".
An approver role scoped to a marketplace names it. The wire vocabulary is an API
contract, not a user-facing one — the portal already translates state values into
badges elsewhere, and "dev-insecure-auth" in particular means nothing to a reader
who has not read the configuration reference.

Unknown future source values render verbatim rather than being dropped: an
unmapped value is still information, and silently omitting a role would be a lie
about what the session holds.

### D4 — No role is a stated state, not an empty list

A session with no role sees one muted line saying it can read the portal and
manage its own tokens. This is the persona the issue asks to verify, and an
empty region under a "Roles" label reads as a failure to load. `claimsTruncated`
adds a line saying the list may be incomplete; it is orthogonal to having roles
and can appear with either.

### D5 — Sign out posts to `/logout`, and is absent under the dev hatch

Spring Security's defaults register `/logout` on the web chain (CSRF-protected,
POST). The menu item submits it with the `X-XSRF-TOKEN` header the portal's own
`api()` client already attaches, then navigates to `/`, which re-enters the OIDC
login. No server code is added or configured — this is why sign-out is in scope
at all without becoming a server capability change.

Under `skills-gateway.dev-insecure-auth=true` there is no session: the filter
invents a principal per request, so "sign out" would appear to fail. The menu
detects the hatch from the role source it already renders
(`dev-insecure-auth`) and replaces the control with a line stating that the
development escape hatch is on and there is no session to end. Alternative
considered: leaving the control and letting it no-op — rejected, a control that
lies is worse than an explanation.

### D6 — The theme control keeps its behavior exactly

Same cycle order, same default, same `localStorage` key, same
"Theme: {current}. Switch to {next} theme." accessible name — so the existing
`theme_toggle_cycles_system_light_dark` test still holds, one level deeper in the
DOM. Inside the menu it gains a visible label ("Theme") beside the icon; it stays
a cycling control rather than becoming three radio items, because `DESIGN.md`
records the cycle as the system's theme control and design-conventions outranks
any harness taste to the contrary.

### D7 — The "Access" sidebar group is removed, not renamed

With tokens under the user menu the group would hold nothing. The Overview
page's "Access tokens" card and its "Manage tokens" action stay as they are —
a second, discoverable route to the same page, and the reason a bookmark-free
user is never stranded.

### D8 — The no-role persona is a story, not an e2e run

The e2e mock identity provider issues `groups: ["sg-gateway-admins"]` on *every*
token (`compose.e2e.yaml`), and that single mapping is also what satisfies the
administrator bootstrap guard — a gateway that maps the admin role to nobody
refuses to start. Giving the suite a second, role-less persona means a second
`requestMappings` entry keyed on something Playwright can vary through an
interactive login, plus a second login helper: not cheap, and the thing under
test is portal rendering, not claim mapping (which `GW_AUTH_0015` already
covers end to end).

So the no-role persona — and the truncated-claim, dev-hatch and failed-`/api/me`
personas with it — is verified where its inputs can actually be varied: a
Storybook story per persona (axe as error, both themes) plus component tests. The
e2e suite verifies what only it can: that the menu opens from the header, that
"Your tokens" lands on the tokens page for a real logged-in session, and that
`/tokens` still resolves when typed directly.

## Risks / Trade-offs

- **Tokens become less discoverable for a first-time user** → The Overview
  page's "Access tokens" card keeps its action, the route is unchanged, and
  `reference/portal.md` gains a line saying where the page now lives. The trade
  is deliberate: a per-user surface should not sit beside estate-wide ones.
- **`/logout` is a Spring Security default, not something this repo configures**
  → A change to the web chain that dropped the default logout support would
  break the control silently. The Playwright step that signs out and lands back
  at a login is what catches that.
- **Two more clicks to reach the theme control** → It is set once and
  remembered. Frequency, not convention, was the only argument for the bar.
- **The menu is the shell's first popup outside a dialog** → It arrives with
  the registry primitive's keyboard and ARIA behavior rather than a bespoke one,
  and the axe-as-error story bar applies to it like everything else.
