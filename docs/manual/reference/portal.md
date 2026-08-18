# Admin portal

The portal is a React single-page application bundled into the gateway jar by
the Maven build and served at `/` behind the OIDC login. It holds no tokens; the
session cookie is its only credential.

## Navigation

A fixed sidebar, grouped:

| Group | Item | Destination |
| --- | --- | --- |
| Gateway | Overview | [`/`](#overview) |
| Gateway | Marketplaces | [`/marketplaces`](#marketplaces) |
| Governance | Audit log | [`/audit`](#audit-log) |
| Governance | Adoption | [`/adoption`](#adoption) |
| Governance | Webhooks | [`/webhooks`](#webhooks) |
| Access | Access tokens | [`/tokens`](#access-tokens) |
| Tools | API reference | `/docs` — the Scalar API reference, not a portal route |

[Marketplace detail](#marketplace-detail) is reached by clicking a marketplace,
not from the sidebar.

The sidebar footer shows the signed-in username from `GET /api/me`. The header
carries a breadcrumb and a dark-mode toggle.

!!! note "There is no logout control"

    Ending a session means clearing the session cookie or logging out at the
    identity provider.

## Conventions across pages

- Every mutation reports through a toast — the action's success message, or the
  server's RFC 7807 `detail` on failure.
- Destructive actions (Reject, Revoke) fire **immediately**; there are no
  confirmation dialogs.
- Buttons disable while their mutation is in flight and swap to a progressive
  label ("Registering…", "Ingesting…").
- **A form's submit button is disabled until every required field is valid**, and
  a muted hint beneath the form (bound to the fields with `aria-describedby`)
  states what it is waiting for. The client rules mirror the server's; whitespace
  never counts as a value. Rules the client cannot know — the URL scheme
  allowlist, the webhook event registry — stay server-side and surface as an
  error toast.
- Every list renders a bare "Loading…" and, on failure, an error paragraph with
  `role="alert"`.
- Timestamps render as raw ISO-8601 strings.

## Authorization

There is **no role model**. Any authenticated session can register
marketplaces, ingest, approve and reject.

The single exception is access tokens, which are scoped per principal
server-side: you only ever see and revoke your own.

---

## Overview

**Route:** `/` · **Heading:** Gateway Overview

The landing page. Three cards, each with counts derived client-side and one
action leading elsewhere. Read-only — nothing here changes state.

**Marketplaces** — chips for the total marketplace count and how many snapshots
are `held`, `approved` and `rejected` across all of them. When any snapshot is
held, a secondary badge reads *"{n} awaiting review"*: the review-queue signal.
Action: **Manage marketplaces**.

**Fetch ledger** — chip with the total recorded fetches. Action: **Open audit
log**.

**Access tokens** — chips for active and revoked counts. Action: **Manage
tokens**.

Data comes from `GET /api/marketplaces`, `GET /api/tokens` and `GET /api/audit`;
counts are computed in the browser, as there is no summary endpoint. While the
queries are in flight the chips show an ellipsis.

---

## Marketplaces

**Route:** `/marketplaces` · **Heading:** Marketplaces

The working surface: registered upstreams and their quarantined, held and
approved snapshots.

### Register marketplace

The header action opens a dialog stating the constraint up front — "The gateway
ingests the upstream default branch; the ref is not selectable."

| Field | Validation |
| --- | --- |
| Name | `^[a-z0-9][a-z0-9_-]*$` — "lowercase letters, digits, `-` and `_`; must not start with `-` or `_`" |
| Clone URL | Must be a valid URL. The scheme allowlist is enforced server-side and surfaces as an error toast. |

**Register** stays disabled until both fields are valid; it enables as soon as the
name matches the pattern and the clone URL parses.

Submits `POST /api/marketplaces`; toasts *Marketplace '{name}' registered*.

### Marketplace cards

One card per marketplace, titled with its name and linking to the detail page.
Beneath: the clone URL, the forge description if captured, and the last upstream
update if known.

The card action is **Ingest** (`POST /api/marketplaces/{name}/ingest`), toasting
*Snapshot {sha12} is {state}*.

### Snapshot table

| Column | Contents |
| --- | --- |
| Commit | First 12 characters of the SHA, monospace. |
| State | Badge — `approved` (primary), `held` (secondary), `rejected` and `revoked` (destructive). |
| Vetting | Badge from `GET /api/snapshots/{id}/vetting`: `vetting clear` (primary), `vetting clear with waivers` (secondary) or `vetting blocked` (destructive). A snapshot the chain never ran against reads *blocked*. The waived case is a separate badge on purpose — an accepted risk must not read as a clean chain. |
| Violation | The ingestion violation, or "—". |
| Decided by | The deciding principal, or "—". |
| Actions | Right-aligned buttons. |

**Approve** and **Reject** appear while the state is `held` or `revoked`; on a
revoked snapshot the approve control reads **Re-approve** and goes through the
same gate. **Reject**
fires immediately and toasts *Snapshot {id} rejected*.

**Approve** opens the review dialog rather than acting immediately — approve is
the moment content becomes reachable by clients, so the verdicts come first.

#### Approve dialog

Titled *Approve snapshot {id}*, it renders the [vetting report](#vetting) for
the snapshot. The confirm control (*Confirm approval of snapshot {id}*) is
disabled for as long as the effective outcome is `BLOCKED`; there is no field to
type past it. The way to enable it is to waive each blocking finding from the
report itself. The server enforces the same rule independently, with `409`, so
the disabled button mirrors policy rather than replacing it.

Confirming calls `POST /api/snapshots/{id}/approve` with no body and toasts
*Snapshot {id} approved*.

#### Waiving a finding

Each blocking finding in the report carries a **Waive finding {rule}** button
that opens an inline form beside it:

| Control | Notes |
| --- | --- |
| **Scope** | *This snapshot only* (default) or *This path in the marketplace* |
| **Expires on** | date input, defaulting to 30 days out; must be in the future — the waiver lapses at the end of the chosen day |
| **Justification** | required; whitespace does not count |

*Record waiver for {rule}* stays disabled until the justification is non-blank
**and** the expiry is still in the future, which is exactly what the server
requires.

*Record waiver for {rule}* calls `POST /api/snapshots/{id}/waivers` and toasts
*Waiver recorded for {rule}*. The finding is then struck through and badged
**waived by {approver} until {date}**, and the outcome badge becomes
**vetting clear with waivers** once nothing is left uncovered.

Below the verdicts, **Accepted risks** lists every waiver whose rule appears in
this run — active, expired and revoked alike — with its rule, scope,
justification, approver and expiry. An active one carries
*Revoke waiver {id}*, which calls `DELETE /api/waivers/{id}`.

**Provenance** is always available, opening a dialog fed by
`GET /api/snapshots/{id}/provenance`: marketplace, upstream URL, upstream SHA,
state, ingested time, decided by, decided at.

Empty states: "No marketplaces registered yet." and, per card, "No snapshots yet
— ingest to fetch the upstream default branch."

---

## Marketplace detail

**Route:** `/marketplaces/{name}` · **Heading:** the marketplace name

Reached by clicking a marketplace name. The page resolves `{name}` from the
marketplace list already held in the browser — there is no per-marketplace
endpoint — so an unknown name renders "Marketplace '{name}' not found." with a
link back.

### Upstream card

Forge metadata captured at registration, best effort: **Forge**, **Project**,
**Description**, **Last upstream update**, **Registered**. Anything not captured
shows "—".

### Snapshots

One card per snapshot showing the short SHA (12 characters, monospace), the
state badge, and the deciding principal once decided. A violation, when present,
renders as destructive text beneath the header row.

The **Show contents** toggle loads `GET /api/snapshots/{id}/content` and renders
one block per declared plugin: name, `source`, optional description, and one
badge per skill found under it. Plugins with no skills show "no skills found".

This is the review surface, and it works on `held` snapshots — inspecting a
snapshot must not require serving it.

### Vetting

Each snapshot card carries a **Vetting** section fed by
`GET /api/snapshots/{id}/vetting`, above the contents toggle and always visible
— the evidence is not behind a click.

It shows the effective chain outcome badge and one block per connector: an icon
and badge for the verdict state, the connector name, a one-line summary, and
every finding as severity badge, rule id, `path:line`, and message. A connector
with nothing to report shows "Nothing found."

A finding an active waiver is suppressing is struck through and badged
**waived by {approver} until {date}**; one that still blocks carries a
**Waive finding {rule}** button. When the effective outcome only clears because
of a waiver, the header adds *the chain objected; active waivers are suppressing
what it found*. Blocking findings are also summarised as a single line naming
what must be waived before approval unblocks. See
[Waiving a finding](#waiving-a-finding) for the form and the
**Accepted risks** list.

A snapshot the chain never ran against says so explicitly, and states that there
is nothing to waive — a snapshot with no evidence cannot be approved at all.

A collapsed *What these connectors can and cannot see* disclosure lists each
configured connector and its self-description, so the limits of the heuristics
are readable at the point of decision. The same section is embedded in the
[approve dialog](#approve-dialog) on the marketplaces page.

### Re-vetting panel

Above the vetting section, each snapshot card carries the re-vetting surface.

| Snapshot | Shown |
| --- | --- |
| State `approved` | A **Re-vet now** button. |
| State `revoked` | *revoked by {revokedBy} on {revokedAt}*, and an **Already fetched by** panel. |
| Anything else | Nothing — re-vetting is about content that is being served. |

**Re-vet now** calls `POST /api/snapshots/{id}/revet` and toasts what the run
concluded: *re-vetted clear*, *could not conclude*, *has a re-vetting violation;
it is still published* (warn mode), or *revoked by a re-vetting violation*
(enforce mode). Why the snapshot was revoked is the card's own `violation` line.

**Already fetched by** lists every identity that received the snapshot's content
through the facade, each with a fetch count and a last-fetch time, from
`GET /api/snapshots/{id}/fetchers`. It is only requested for a revoked snapshot.
When nobody fetched it, the panel says so rather than showing an empty list.

The way back is on the [marketplaces](#marketplaces) page: a revoked snapshot's
approve control reads **Re-approve** and goes through the ordinary gate. See
[Re-vetting approved content](../guides/re-vetting.md).

### Retention controls

Each snapshot card carries its retention state and the control that changes it:

| Snapshot | Shown |
| --- | --- |
| Not deleted, state `held`, `rejected` or `revoked` | A **Delete** button. |
| Not deleted, state `approved` | Nothing — an approved snapshot is served by the facade and the gateway refuses to delete it. |
| Deleted | A destructive `deleted` badge, "restorable until {purgeAfter}", and a **Restore** button. |

**Delete** calls `DELETE /api/snapshots/{id}` and toasts *Snapshot {id} deleted;
it can be restored*. **Restore** calls `POST /api/snapshots/{id}/restore` and
toasts *Snapshot {id} restored*. Both fire immediately, like every other
mutation in the portal — deletion here is a reversible mark, not a purge.

!!! warning "The restore deadline is the real one"

    Once `purgeAfter` has passed, a compaction run removes the snapshot and its
    quarantine ref permanently and the card disappears. See
    [Snapshot retention](retention.md).

Empty state: "No snapshots yet."

---

## Audit log

**Route:** `/audit` · **Heading:** Audit log

The ledger and its export surface. Subtitle: "Append-only ledger of every facade
fetch and administrative action, exportable to an external compliance system."

### Export

A **Download ledger (NDJSON)** link pointing at `/api/audit/export` — a plain
same-origin, session-authenticated download, not a fetch through the API client.

### Export sinks

An inline form with **Sink name** and **Target URL** posts to
`POST /api/audit/sinks`. **Add sink** stays disabled until the name matches
`^[a-z0-9][a-z0-9_-]*$` and the target URL parses with a scheme; the scheme
allowlist itself stays server-side. The response opens the same show-once secret dialog as
[Access tokens](#access-tokens) and [Webhooks](#webhooks).

| Column | Contents |
| --- | --- |
| Name | The sink name. |
| Target URL | Where batches are POSTed. |
| Position | The sink's cursor — the last ledger sequence handed to it, monospace. |
| Behind | Entries not yet handed over, as "{n} entries". |
| Status | `enabled` (primary badge) or `disabled` (secondary). |
| Actions | **Replay** and **Delete**, both firing immediately. |

**Replay** sets the cursor to `0` — the whole ledger, from the beginning — and
toasts *Sink '{name}' will replay the ledger*. Replaying to an arbitrary
position is API-only (`PUT /api/audit/sinks/{id}/cursor`).

**Delete** calls `DELETE /api/audit/sinks/{id}`, taking the sink's delivery
channel with it, and toasts *Sink '{name}' deleted*.

Sink deliveries are ordinary webhook deliveries, so their attempts appear on the
[Webhooks](#webhooks) page rather than here.

Empty state: "No export sinks yet."

### Ledger

The table, from `GET /api/audit`.

It is **schema-less**: columns are the keys of the first returned row, in
order, and every cell renders as monospace text with nulls shown as "—". In
practice those columns are `id`, `ts`, `source`, `principal`, `marketplace`,
`event`, `ref` and `sha`.

There is no filtering, search or paging — it is a recent-activity view rather
than an investigation tool.

Empty state: "No fetches recorded yet."

---

## Adoption

**Route:** `/adoption` · **Heading:** Adoption

The [adoption and staleness reports](api/adoption.md), read-only. Subtitle:
"Who fetches what through the facade, aggregated from the append-only ledger,
and which identities are not on the served tip." With
[role enforcement](#authorization) enabled both underlying reads require the
auditor role, so a session without it sees the page's error state.

### Window and totals

A **Report window** button group — 7, 30 or 90 days, default 30, the active
choice pressed — drives `GET /api/adoption?days=`. Beside it, stat chips:
total fetches in the window, marketplaces fetched, and stale identities.

### Adoption by marketplace

One row card per marketplace fetched in the window: name, a `serving` /
`not serving` badge, and chips for fetches, identities and the last fetch.
Inside, the per-SHA breakdown:

| Column | Contents |
| --- | --- |
| Snapshot SHA | First 12 characters, monospace; full SHA on the tooltip. |
| Fetches | Content-transferring fetches of this SHA in the window. |
| Identities | Distinct identities that fetched it. |
| Last fetch | Most recent, as a raw ISO-8601 timestamp. |
| Tip | `current` (primary badge) or `superseded` (secondary). |

Empty state: "No fetches in the last {n} days. Adoption appears once content
is fetched through the facade."

### Stale identities

The [staleness report](api/adoption.md#get-apiadoptionstaleness), window-free:
identity, marketplace, last received SHA, the served tip it diverges from —
or a destructive `not serving` badge when the marketplace stopped serving
entirely — and the last fetch time. The page says what the report is: facts,
not verdicts.

Empty state: "Every identity is on the served tip."

---

## Webhooks

**Route:** `/webhooks` · **Heading:** Webhooks

"Snapshot lifecycle events are POSTed to each subscriber that filters for them,
signed with HMAC-SHA256 and retried with backoff until delivered."

### Add subscriber

An inline form with **Subscriber name**, **Target URL** and **Events** — the
last defaults to `*` and is placeholder-hinted with
`snapshot.approved,snapshot.rejected`. **Add subscriber** stays disabled until the
name matches `^[a-z0-9][a-z0-9_-]*$` and the target URL parses with a scheme;
only then is `POST /api/webhooks` called. Everything else — the scheme allowlist
and the set of known event names — is enforced server-side and surfaces as an
error toast.

The response opens a show-once dialog — "This signing secret is shown exactly
once — copy it now." — with the `whsec_…` value in a code block and a clipboard
button that flips to a checkmark for two seconds, the same pattern as
[Access tokens](#access-tokens).

### Subscribers

| Column | Contents |
| --- | --- |
| Name | The subscriber name. |
| Target URL | The registered endpoint. |
| Events | The filter, rendered as a chip; `*` displays as "all events". |
| Status | `enabled` (primary badge) or `disabled` (secondary). |
| Actions | **Delete**, which fires immediately. |

**Delete** calls `DELETE /api/webhooks/{id}` and toasts *Subscriber '{name}'
deleted*. It removes the delivery history with the subscriber.

Empty state: "No subscribers yet."

### Delivery attempts

From `GET /api/webhooks/deliveries` — the operator's view of a failing
integration.

| Column | Contents |
| --- | --- |
| Event | The lifecycle event name. |
| Subscriber | Resolved from the subscriber list held in the browser; falls back to the raw id. |
| State | Badge — `delivered` (primary), `failed` (destructive), `pending` (secondary). |
| Attempts | Attempts made so far. |
| Last response | The last HTTP status, else the last error, else "—". |
| Queued | Enqueue timestamp. |

Read-only: there is no manual redelivery and no editing. Empty state: "No
deliveries yet."

The secret is never re-displayed anywhere on this page. See
[Receiving lifecycle webhooks](../guides/lifecycle-webhooks.md) for the payload,
headers and signature verification.

---

## Access tokens

**Route:** `/tokens` · **Heading:** Access tokens

"Personal access tokens authenticate git clients against the facade. Values are
hashed at rest and shown exactly once."

An inline form with a **Token name** field and a **Create token** button posts to
`POST /api/tokens`. **Create token** stays disabled until the name field holds a
non-blank value — the name is trimmed before it is sent. The response opens a
show-once dialog — "This value is shown
exactly once — copy it now. Only a hash is stored." — with the token in a code
block and a clipboard button that flips to a checkmark for two seconds.

| Column | Contents |
| --- | --- |
| Name | The name you gave it. |
| Created | Creation timestamp. |
| Status | `active` (primary badge) or `revoked` (destructive). |
| Actions | **Revoke**, shown only while active. |

**Revoke** calls `DELETE /api/tokens/{id}` and toasts *Token '{name}' revoked*.
It fires immediately. Revocation is recorded rather than deleted: the row stays
with a `revoked` badge.

Empty state: "No tokens yet."

See [Consuming approved skills](../guides/consuming-skills.md) for using a token
with a git client.
