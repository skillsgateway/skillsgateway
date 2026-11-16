# Design: portal-token-homes

## Context

- `GET /api/v1/marketplaces` already answers every signed-in user with
  `servedSha`, every snapshot's `state`, `createdAt`, `decidedBy`, `decidedAt`,
  `violation`, plus `lastIngestOutcome` and `lastIngestReason`. Nothing needs a
  role. Snapshot content, file trees and several findings are approver-gated
  and stay so.
- `/api/v1/tokens/machine` (list, create, rotate, delete) is admin-only and
  deliberately unreachable by any machine credential (`MachineApiRegistry`).
- The sidebar already hides entries by `needs` role (`app-layout.tsx`), and
  `RoleRequired` renders the "needs the administrator role" statement.
- `/tokens` renders `TokensPage` from the user menu and the Overview card.
- No API exposes the list of scope names; they are keys of the registry map.

## Goals / Non-Goals

**Goals:** one home per token kind; a portal path for provisioning system
tokens; a plain-language status view; no widening of what any role reads.

**Non-Goals:** new token types or scopes; any change to who may call the token
endpoints (the lifetime rule of D10 is the one API behaviour that changes); further Security-section pages; per-plugin "what would change".

## Decisions

**D1. Security section as a nested route group.** `/security` is a section
whose only child is `/security/tokens` (Personal access tokens), mirroring how
`/integrations` nests. The user-menu item "Security" navigates to `/security`,
which redirects to its first page. `/tokens` becomes `<Navigate
to="/security/tokens" replace>`. The section is not in the sidebar: the issue
places it under the user menu, and a role-less user would otherwise see an
extra group. Alternative (a sidebar group) rejected: it would give personal
tokens a second home again.

**D2. System access tokens at `/configuration/tokens`**, in the Configuration
group beside Vetting chain, `needs: "admin"`; the page is wrapped in
`RoleRequired` so a non-admin at the address gets the usual statement, and the
server refuses independently. Breadcrumb: "System access tokens".

**D3. One new endpoint for scopes.** `GET /api/v1/tokens/machine/scopes`
returns the registry's scope names (sorted, with the routes each reaches, so
the form can show what a scope grants). Alternatives: hard-code the list in the
frontend (drifts the first time a scope is added; the API refuses an unknown
scope, so drift fails late, at submit time) or annotate `allowableValues`
(static, same drift). A route of the same `admin`-only, machine-unreachable
class as its siblings adds no new trust surface. It is additive within the
major. The issue's "no change to the API's token endpoints" is read as no
change to existing behaviour; this is called out for the owner in the PR.

**D4. The cleartext lives only in React state.** The create and rotate
responses are held in component state of a reveal dialog and dropped when it is
closed; never in TanStack Query's cache (the mutation result is not retained
past the dialog: `gcTime: 0` on the mutation, and the list query is
invalidated, not seeded from the response), `localStorage`, `sessionStorage`,
the URL or a log. The personal-token page's pattern is reused as a shared
component so the two cannot diverge. A test inspects query cache and both
storages after close.

**D5. Validation is the server's, mirrored for guidance.** The form blocks only
what it can state locally (no scope chosen, no lifetime chosen) and displays the
server's 422 problem detail verbatim for the rest, so the portal cannot
disagree with the API. Lifetimes are a fixed list (30 days, 90 days, 180 days,
1 year, never), none preselected, no custom date. A deployment with a
configured cap refuses the longer ones and never; the refusal is shown.

**D6. Markers.** A credential is *expiring soon* within 14 days of `expiresAt`
and *never used* when `lastUsedAt` is null and it is older than 24 hours (a
brand-new one has not had a chance). Marks are text with an icon, not colour
alone. Both are constants in the page, not configuration (the stop rule).

**D7. Estate.** System access tokens are secrets and are API-managed like PATs;
a declarative estate cannot hold a credential, so the estate obligation is met
by this statement (AGENTS.md: "state in its design why the object is
deliberately API-only").

**D8. Status view as a component, not a role-gated page.** A "Status" panel on
the marketplace page (above the tabs' content, visible to everyone) computes
from the marketplace payload:
- *Served now:* the snapshot whose `sha` equals `servedSha`; commit, `decidedAt`
  as "serving since", `decidedBy` ("automatic approval" when the existing
  automatic-approval identity is recorded, otherwise the person). If none:
  "nothing is served", with the reason: no snapshot was ever approved, or the
  approved one was withdrawn (an approved/revoked record exists but
  `servedSha` is null).
- *Newer:* the newest non-deleted snapshot by `createdAt` that is not the
  served one and is newer than it: state *awaiting approval* (held), *rejected*,
  *revoked*, with age since `createdAt`; or, when `lastIngestOutcome` is
  `failed`, *ingest failed* with `lastIngestReason`. Nothing newer: "up to
  date".
- *Who decides:* for held, "an approver of this marketplace (or an
  administrator) decides this; ask your gateway administrators". No names:
  listing role holders needs a read that a role-less session does not have today.
Alternative of a new `/status` endpoint rejected: every field above is already
in the payload every signed-in user receives. A test pins this by rendering the
view from a no-role session's payload only. If the payload lacks a field the
view needs (the approval identity), tasks 6.1 stops and re-opens this decision
rather than widening a read.

**D10. Lifetime policy (owner decision, reverses part of `GW_AUTH_0024`).**
`GW_AUTH_0024` made a machine credential's expiry mandatory, with a 90-day
built-in cap, because "mandatory expiry" over an absent cap admits
`now + 100 years`, the never-expiring credential spelled differently, and a
long-lived control-plane credential "should be a stated choice, not the
consequence of leaving a property blank". The owner has chosen to allow never
and one year, as GitHub (366-day default cap), Artifactory (1 year default,
never for admins), Langfuse and Nexus service accounts (1 year, never) do.
This design keeps the quoted principle: the request body carries an explicit
`neverExpires: true` (an omitted expiry is still refused, so the existing refusal
test stays valid), a configured `skills-gateway.tokens.max-ttl` refuses never
and caps the lifetime as before, the built-in cap becomes 365 days, rotation
preserves no-expiry, and `machine_credentials_expire` is dropped by
`V2__machine_credentials_may_never_expire.sql`: the gateway has users, so an
existing database is migrated, never recreated, and `V1` stays untouched. Only administrators can create one, it appears in
the list as "never expires", and the never-used marker (D6) matters more for it.
Consequences to carry in the PR: the boundary values in the `GW_AUTH_0024` SVC
change (cap 90 to 365 days) and its requirement text is amended; that is a
requirement change approved by the owner, not a weakened test. The personal
token list (`token-lifetimes.ts`) drops 7 days and "No expiry" and gains 180
days and 1 year, for the page and the wizard alike; the server still accepts a
PAT without expiry when no cap is configured (API unchanged), the portal just
stops offering it.

**D9. Term.** "System access token" everywhere in the portal and docs; the
token reference says once that the API calls it a machine API credential.

## Risks / Trade-offs

- A sixth place reading `decidedBy` to say "automatic" is brittle; reuse the
  existing helper for that identity if present, else show the identity as is.
- Hiding the Security section from the sidebar makes it less discoverable; the
  user menu is where the issue and GitHub/GitLab put it.
- Redirect keeps old bookmarks working but the breadcrumb for `/tokens` changes.
- Marks use fixed thresholds that an organisation may disagree with; they are
  advisory, the list shows the raw dates.
