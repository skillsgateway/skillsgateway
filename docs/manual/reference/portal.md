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
| Governance | Vetting | [`/vetting`](#vetting-governance) — **shown only to administrators** |
| Governance | Adoption | [`/adoption`](#adoption) |
| Governance | Webhooks | [`/webhooks`](#webhooks) |
| Reference | API reference | `/docs` — the Scalar API reference, not a portal route |
| Reference | Documentation | this manual — leaves the portal, opens in a new tab |
| Reference | Source code | the project repository — leaves the portal, opens in a new tab |

[Marketplace detail](#marketplace-detail) is reached by clicking a marketplace,
not from the sidebar, and [Snapshot contents](#snapshot-contents) from a
snapshot on that page. [Access tokens](#access-tokens) is a per-user concern, not
estate-wide navigation — it is reached from the user menu, described next, and
from the Overview page's Access tokens card; `/tokens` remains a resolvable
address for existing bookmarks and links.

The sidebar footer states the running build — *Skills Gateway 0.3.0* — read from
`GET /api/v1/me`. It is the build artifact's own version and nothing a
deployment can set, so it cannot claim to be a version it is not; a gateway run
from an exploded build carries no build information and the footer is then
absent entirely rather than saying "unknown".

## User menu

The header's breadcrumb sits opposite a menu named after the signed-in user
(`GET /api/v1/me`). Opening it shows:

- **Signed in as** the username.
- **Roles** — one line per effective role the session holds, said in the
  reader's own terms rather than the API's wire values: `admin — from your
  identity provider`, `approver of acme-skills — granted in the portal`, and so
  on for a role sourced from configuration or from the local development
  authentication escape hatch. A session holding no role is told so, together
  with what it can still do (read the portal, manage its own access tokens),
  rather than shown an empty list. If the identity provider truncated the
  membership claim, a line says the role list may be incomplete.
- The **theme control** — three-state, cycling **system → light → dark**: it
  defaults to _system_ (follow the operating system's appearance), and its icon
  and label state the chosen mode — a monitor for system, a sun for light, a
  moon for dark. The choice is remembered in the browser (`localStorage`);
  _system_ tracks the OS setting as it changes.
- **Your tokens**, linking to [Access tokens](#access-tokens).
- **Sign out**, which ends the browser session and returns to a fresh login.
  Under the `skills-gateway.dev-insecure-auth` development escape hatch there is
  no session to end, so the menu states that instead of offering a control that
  would appear to fail.

The menu only reports what the session holds; it never hides or disables a
control by role — the server is the sole authority on what a request may do,
and refuses what it must regardless of what the menu shows.

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
  allowlist — stay server-side and surface as an error toast.
- Every list renders a bare "Loading…" and, on failure, an error paragraph with
  `role="alert"`.
- Timestamps render in the reader's own locale and time zone. The exact
  instant is never lost: it stays in the `<time datetime>` attribute and on
  the hover tooltip, so an auditor can always recover the recorded value. A
  timestamp the portal cannot parse is shown verbatim rather than hidden, and
  an absent one renders as an em dash.

## Authorization

The server refuses mutations, the audit
surface and the [snapshot contents reads](#snapshot-contents) to sessions without an
applicable role — see
[Delegated administration](../guides/delegated-administration.md); the portal
surfaces those refusals as errors rather than hiding controls.

Access tokens are scoped per principal server-side regardless: you only ever
see and revoke your own.

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

Data comes from `GET /api/v1/marketplaces`, `GET /api/v1/tokens` and `GET /api/v1/audit`;
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

If the clone URL already matches a registered marketplace — comparison ignores
case, a trailing slash and a `.git` suffix — the dialog shows a **warning** naming
the existing marketplace(s) and holds **Register** shut until *Register anyway* is
ticked. This is a warning, not a block: the same upstream under a different name is
a legitimate test setup, so the server still accepts it; the acknowledgement only
makes the collision deliberate rather than silent.

Submits `POST /api/v1/marketplaces`; toasts *Marketplace '{name}' registered*. The
server runs the identical duplicate-URL check independently against every
registered marketplace, not only the list this dialog already had loaded, and
returns what it found in the response's `warnings`; each one is shown as its
own warning toast, so a collision this dialog's own check missed still reaches
the operator.

### Marketplace table

One sortable row per marketplace:

| Column | Contents |
| --- | --- |
| (expander) | A chevron; clicking the row (or the chevron) reveals the marketplace's snapshot table in place. |
| Name | The marketplace name, a link to its [detail page](#marketplace-detail). |
| Source | The detected forge (or the clone URL's host), with the clone URL beneath. |
| Latest snapshot | The newest snapshot's state badge and its vetting outcome badge. |
| Upstream updated | The last upstream update if known, else "—". Sortable. |
| Snapshots | A count badge. |

Expanding a row reveals **Ingest**
(`POST /api/v1/marketplaces/{name}/ingest`, toasting *Snapshot {sha12} is {state}*),
an **Open detail** link, and the [snapshot table](#snapshot-table) with its
review actions.

### Snapshot table

| Column | Contents |
| --- | --- |
| Commit | First 12 characters of the SHA, monospace. |
| State | Badge — `approved` (primary), `held` (secondary), `rejected` and `revoked` (destructive). |
| Vetting | Badge from `GET /api/v1/snapshots/{id}/vetting`: `vetting clear` (primary), `vetting clear with waivers` (secondary) or `vetting blocked` (destructive). A snapshot the chain never ran against reads *blocked*. The waived case is a separate badge on purpose — an accepted risk must not read as a clean chain. |
| Violation | The ingestion violation, or "—". |
| Decided by | The deciding principal, or "—". |
| Actions | Right-aligned buttons. |

**Approve** and **Reject** appear while the state is `held` or `revoked`; on a
revoked snapshot the approve control reads **Re-approve** and goes through the
same gate. **Reject**
fires immediately and toasts *Snapshot {id} rejected*.

**Approve** opens the review dialog rather than acting immediately — approve is
the moment content becomes reachable by clients, so the verdicts come first.

While a snapshot is inside the configured
[minimum release age](configuration.md#minimum-release-age), the approve control
is disabled and reads **Eligible in {remaining}** — from
`GET /api/v1/snapshots/{id}/release-age`, so the portal never computes the deadline
from the browser's clock. **Reject** stays enabled: rejection is never age-gated.
With the gate off (the default) the control reads **Approve** as before, and a
snapshot whose eligibility has not been fetched yet is never disabled on
suspicion — the server is the gate.

#### Approve dialog

Titled *Approve snapshot {id}*, it renders the [vetting report](#vetting) for
the snapshot. The confirm control (*Confirm approval of snapshot {id}*) is
disabled for as long as the effective outcome is `BLOCKED`; there is no field to
type past it. The way to enable it is to waive each blocking finding from the
report itself. The server enforces the same rule independently, with `409`, so
the disabled button mirrors policy rather than replacing it.

Below the report, a *Plugin names already in use* section lists each plugin
name the snapshot introduces that looks like one another marketplace already
serves, with the manifest line declaring it and the incumbents it resembles.
While any is uncovered the confirm control stays disabled. Each carries a
**Waive name collision for {name}** button opening the same waiver form, offering
*This snapshot only* as the only scope. The snapshot card does not shut
**Approve** for a collision, since this dialog is where it is waived.

The dialog is also shut by the minimum release age, and says so in its own note:
that one has no way past it in the portal at all, and needs none — it opens by
itself at the stated time.

Confirming calls `POST /api/v1/snapshots/{id}/approve` with no body and toasts
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

*Record waiver for {rule}* calls `POST /api/v1/snapshots/{id}/waivers` and toasts
*Waiver recorded for {rule}*. The finding is then struck through and badged
**waived by {approver} until {date}**, and the outcome badge becomes
**vetting clear with waivers** once nothing is left uncovered.

Below the verdicts, **Accepted risks** lists every waiver whose rule appears in
this run — active, expired and revoked alike — with its rule, scope,
justification, approver and expiry. An active one carries
*Revoke waiver {id}*, which calls `DELETE /api/v1/waivers/{id}`.

**Provenance** is always available, opening a dialog fed by
`GET /api/v1/snapshots/{id}/provenance`: marketplace, upstream URL, upstream SHA,
served SHA, state, ingested time, decided by, decided at — and, for a snapshot
with resolved external plugin sources, the closure: each external plugin with
the URL it was fetched through and the commit it resolved to.

Empty states: "No marketplaces registered yet." and, in an expanded row, "No
snapshots yet — ingest to fetch the upstream default branch."

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

### Vetting chain (administrators)

**Shown only to a session holding the `admin` role**, above the snapshots. Fed by
`GET /api/v1/marketplaces/{name}/vetting-chain`, which the server refuses to anyone
else — the switch that governs the chain, and the visibility of its settings, sit
above the content they govern.

The card's description links to [Vetting](#vetting-governance), which governs the
default every setting here falls back to and lists every marketplace that departs
from it.

It draws the [same flow](#the-chain-flow) without verdicts, headed by how much of
the chain runs and what is off:

```text
2 of 3 vetters run · secret-scan off for this marketplace
```

Every configured vetter appears in the order it runs, each showing `enabled` or
`disabled`, a chip naming **where that state came from** (`this marketplace`,
`global`, `default`), and — for a vetter that is off — who switched it off.
Opening a node gives the same source in full, the vetter's version, whether it
is built in or external, when the deciding setting was last set, and the note left
with it.

The node's detail also carries the switch: an optional reason field and a single
control that enables or disables that vetter **for this marketplace**, calling
`PUT /api/v1/vetting/vetters/{name}/toggle`. A per-marketplace setting overrides
the global one. Every change is written to the audit ledger with the acting
administrator, the vetter, the scope, the new state and the reason.

Switching vetters off narrows the evidence behind an approval; it never makes
one automatic. What that means for a run is described in
[Switching a vetter off](../concepts/vetting.md#switching-a-vetter-off).

Beneath the drawing sit the two chain-level controls, fed by
`GET /api/v1/marketplaces/{name}/vetting-chain-settings`.

**When a vetter fails** is a two-option control — *Run every vetter* or *Stop
after a failure* — calling `PUT /api/v1/vetting/chain-mode` for this marketplace,
with an optional reason. The hint beside it states the cost rather than leaving
it to be discovered on a snapshot: stopping early spares the vetters after a
failure, and it also means a reviewer no longer sees everything that is wrong
with a snapshot in one pass, and that a run which stopped is blocked until it has
been run again — even after the finding that stopped it is waived. The line ends
with where the current mode came from.

**The order they run in** is a numbered list with a **Move _vetter_ up** and a
**Move _vetter_ down** control on every row. The controls are ordinary buttons
with names of their own, so the reordering is operable by keyboard and announced
as what it does; there is no drag gesture to have an equivalent for. Movements
are local until **Save order** writes the whole arrangement through
`PUT /api/v1/vetting/chain-order` — one intended reordering is one audited change,
not one per hop — and **Discard changes** puts it back. While an arrangement is
unsaved the drawing above shows the proposed order and the list says it is not
saved yet.

### Snapshots

Snapshots are grouped by what they are for, not listed alike, and **one is open
at a time** — so the page does not grow longer with every ingest.

| Section | Holds | Shown as |
| --- | --- | --- |
| **Awaiting decision (n)** | Every `held` or `revoked` snapshot that is not deleted, newest first | The newest open as a card; the rest one line each, with its delta |
| **Serving** | The snapshot whose commit the facade answers with, read from the marketplace's `servedSha` | One line. "Nothing is served." when `servedSha` is null — and, if a snapshot is still recorded approved, that it was withdrawn |
| **Earlier snapshots (n)** | Everything else: rejected, deleted, approved but no longer served | A collapsed count |

Awaiting comes first because it is the only section with a pending action. Two
snapshots can await a decision at once. When nothing does, the served snapshot
is the one open. **Open** on any line opens that snapshot's card in its place.

The open snapshot, its tab and its file are in the address, so a link to the
evidence restores all three:

```text
/marketplaces/acme?snapshot=41&tab=contents&path=plugins/hello/skills/hello/SKILL.md
```

#### The card

Top to bottom, in this order on purpose:

1. **Identity** — the short SHA, the state badge, when it was ingested and by
   whom, who decided it, and the retention control
   ([Delete / Restore](../guides/snapshot-retention.md)).
2. **The delta line** — what is arriving, how big it is, and against what:

    ```text
    1 skill added, 1 modified · 3 files · +48 −7 · vs 65f64622
    ```

    File and line counts come from `GET /api/v1/snapshots/{id}/diff` (against
    what is served); skill counts from `GET /api/v1/snapshots/{id}/content-diff`
    (against the last approved snapshot). Skill counts are left out when those
    two baselines differ, rather than mixing counts taken against two commits.
    With nothing served the line says approving serves all of it; when a read
    was cut at its limit it says the figures are lower bounds.
3. The violation and, for an approved or revoked snapshot, the
   [re-vetting panel](#re-vetting-panel).
4. **Tabs** — the evidence. Only the open tab loads.

    | Tab | Shows |
    | --- | --- |
    | **Vetting** (default) | The [vetting report](#the-chain-flow): chain outcome, verdicts, findings, waivers |
    | **Contents** | The [file explorer](#snapshot-contents), inside the card |
    | **Diff** | Changes since the last approved snapshot — plugins and skills marked added, changed, moved or removed, only what changed, from `content-diff`. With nothing approved yet it says there is no baseline |
    | **Inventory** | What the snapshot ships: one block per declared plugin with its `source`, description and one badge per skill, from `GET /api/v1/snapshots/{id}/content` |
    | **Provenance** | Upstream URL and SHA, the served SHA, who decided it and when, and the closure of external plugin sources |

5. **The decision** — **Approve** (**Re-approve** for a revoked snapshot) and
   **Reject**, only for a snapshot awaiting one.

The decision is last because it rests on everything above it: an approval
control is never shown above the evidence. When the gateway would refuse the
approval — vetting blocked it, the minimum release age has not passed, or the
four-eyes rule refuses you — **Approve** is disabled and the reason is on the
card, rather than the press failing. **Approve** opens the same review dialog as
the [Marketplaces](#marketplaces) page; **Reject** fires immediately.

When other snapshots await a decision, the card names them. Approving this one
does not retire them: they stay held, and approving an older one afterwards
would serve content older than this.

This is the review surface, and it works on `held` snapshots — inspecting a
snapshot must not require serving it.

### Set up a client

A card at the **top of the page**, above the upstream metadata, opening a wizard
that composes for this marketplace everything a consumer needs — every URL
derived from the address the browser is already on.

The card reads the marketplace's own state:

| State | The card says | And |
| --- | --- | --- |
| The facade serves a commit | **Use this marketplace** | offers the wizard |
| Nothing served | **Not being served yet** | states that a clone is answered with `404` until one is, and that this is not a credential problem — a wrong or revoked token is answered with `401`. The wizard repeats it, for anyone who opens it without reading the page |

"Serving" is the marketplace read's `servedSha`, read from the published
repository — not inferred from an approved snapshot existing, which is wrong
after a withdrawal that left one approved and serves nothing.

Inside the wizard:

1. **Personal access token** — the same show-once creation flow as the
   [Access tokens](#access-tokens) page. The name defaults to
   `{marketplace}-client` and the control disables again if it is emptied or left
   whitespace. **Expires** is a button group — 7, 30 or 90 days, or no expiry —
   defaulting to 30 days rather than to never; the
   gateway's own cap
   ([`skills-gateway.tokens.max-ttl`](configuration.md#access-tokens)) is not
   exposed to the browser, so a longer choice than the deployment allows is
   refused by the server and the refusal is shown beneath the field. A token
   minted here is filled into the snippets **only while the wizard stays open**;
   closing it drops the value, and no previously issued token's value is ever
   shown.
2. **Store the credential** — a `git credential approve` line for this host.
   This is the **primary copy target**, marked by a highlighted snippet box,
   because a consumer who copies one thing should copy this. Its copy control is
   the same corner icon button the other snippets use, so the three read as one
   family.
3. **Add the marketplace to Claude Code** —
   `claude plugin marketplace add {origin}/git/{name}`.
4. **Clone directly** — the CI-shaped `git clone` URL with the token inline.

The remaining snippets have icon copy buttons. Until a token is minted the
snippets carry the `<YOUR_TOKEN>` placeholder.

### Vetting

The snapshot card's **Vetting** tab — the tab a card opens on — is fed by
`GET /api/v1/snapshots/{id}/vetting`.

#### The chain flow

Above the per-vetter list, the chain is drawn as an ordered flow — one node
per step, left to right:

```text
Ingest → secret-scan → prompt-injection → Outcome → Approval
```

The order is the order the vetters ran, taken from the run's own recorded
positions; for a snapshot the chain has not run against yet, it is the configured
chain in the order it will run.

The flow never wraps: nodes narrow their names before the row breaks, and on a
screen too narrow for the whole chain it scrolls sideways — faded at the edge it
continues past — so the gate is never stranded on a line of its own, away from
the result it follows.

Above the nodes, one sentence states the answer and where the chain stopped, so
it can be read without opening anything:

```text
Blocked at step 1 · secret-scan found 1 critical finding
Clear · 4 vetters, 0 findings
Clear with waivers · 2 findings accepted
Stopped at step 1 · secret-scan found 1 critical finding, so 2 later vetters
                    did not run — re-vet to see what they say
```

The last of those is the one a reader must not skim past: a chain that
[stopped early](../concepts/vetting.md#stopping-the-chain-after-a-failure) has a
smaller verdict set, not a cleaner one, so the sentence says how many vetters
never looked and what to do about it.

How to read a node:

| Part | Meaning |
| --- | --- |
| Stage label | Where the node sits: `Source`, `Step N` for each vetter in run order, `Result`, `Gate`. |
| Name | `Ingest`, a vetter's name, `Outcome`, `Approval`. |
| State word | `pass`, `warn`, `fail`, `error`, `pending`, `skipped` (switched off), `not reached` (the chain stopped before it), `not run` (no verdict at all) for a vetter; `clear`, `clear with waivers`, `blocked` for the outcome; `open` or `closed` for the approval gate. The word is always present — colour never carries a state on its own. |
| Top edge | The same state, as colour: the accent for a pass or warn, red for a fail or error, a plain hairline for anything that reached no conclusion. A second, scannable carrier of the word above it, never a replacement for it. |
| Finding count | How many findings that vetter raised, when it raised any. |
| `external` chip | The verdict comes from an operator-configured external service, not from a built-in vetter. |
| `N waived` chip | How many of that vetter's findings an active waiver is suppressing. The vetter keeps the verdict it reached: an accepted risk is never redrawn as a pass. |

The stage the chain arrives at — `Result` here, `Gate` on the marketplace chain —
is drawn as a filled surface rather than an outline, so the end of the chain reads
as the end.

Every node is a button. Activating one opens its detail:

- **A vetter** — its verdict, its version, whether it is built in or external,
  its self-description, and its findings, with a waived one struck through and
  badged. A `skipped` node states that an administrator switched the vetter
  off for this marketplace and that the scope and reason are on the
  [marketplace's vetting chain](#vetting-chain-administrators), which is
  administrator-only. A `not reached` node states that the chain stopped before
  it, that this is an absence rather than a result, and that a waiver on the
  finding which stopped the chain lets the next run get this far without standing
  in for the verdict this vetter never gave.
- **The outcome** — how the aggregation reached its answer, what the vetters
  themselves recorded, which vetters are objecting, the vetters the run never
  reached, and the findings that still need a waiver.
- **Ingest** and **Approval** — what the step is, and why the gate is where it is.

The flow is an overview; the per-vetter list below it is where findings are
read side by side and a waiver is written next to the one being accepted. Both
are always shown.

It shows the effective chain outcome badge and one block per vetter: an icon
and badge for the verdict state, the vetter name, a one-line summary, and
every finding as severity badge, rule id, `path:line`, and message. A vetter
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

A collapsed *What these vetters can and cannot see* disclosure lists each
configured vetter and its self-description, so the limits of the heuristics
are readable at the point of decision. The same section is embedded in the
[approve dialog](#approve-dialog) on the marketplaces page.

### Chain staleness

Enabling or disabling a vetter, or changing the chain's mode or order, **re-runs
nothing**. For approved content the re-vetting sweep converges on it; for a
snapshot still at the approval gate nothing does. So a snapshot ingested before
a chain change can be approved on evidence a vetter in the chain today never
produced.

The vetting section says so, against the evidence it qualifies:

| State | Shown |
| --- | --- |
| The run came from the chain in force | Nothing. A marking on every snapshot would be noise. |
| The run came from a different chain | A notice naming **both** chains — the one that produced the evidence and the one in force — and a **Re-run the chain now** button. |
| The run records no chain identity | *This run records no chain identity, so whether it matches the chain in force is unknown.* No alarm and no button: the gateway does not know that anything is wrong. |

A chain is described as `vetter@version,…;mode=…`, and — when an administrator
has switched vetters off — `;disabled=[…]`. A switched-off vetter stays named in
the chain and is recorded on the run as a `disabled` verdict, so a toggle shows
up in the `disabled=` part rather than by a name disappearing.

**Approval is not blocked by this**, and no configuration makes it block. The
gateway states the fact and the reviewer decides, the same as for an override or
a waiver. If they approve anyway, the ledger records
`snapshot-approved-on-superseded-chain` naming both chains, beside the approval
rather than instead of it.

**Re-run the chain now** calls `POST /api/v1/snapshots/{id}/revet`. For a held
snapshot that is a *refresh*, not a re-vetting: the chain runs, the run is
recorded, the snapshot stays held, and nothing is announced or retracted —
there is nothing published to retract.

### Re-vetting panel

Above the tabs, the snapshot card carries the re-vetting surface.

| Snapshot | Shown |
| --- | --- |
| State `approved` | A **Re-vet now** button. |
| State `revoked` | *revoked by {revokedBy} on {revokedAt}*, and an **Already fetched by** panel. |
| Anything else | Nothing here — a held snapshot's evidence is refreshed from the [chain staleness notice](#chain-staleness) instead, which is where the reason to refresh it appears. |

**Re-vet now** calls `POST /api/v1/snapshots/{id}/revet` and toasts what the run
concluded: *re-vetted clear*, *could not conclude*, *has a re-vetting violation;
it is still published* (warn mode), or *revoked by a re-vetting violation*
(enforce mode). Why the snapshot was revoked is the card's own `violation` line.

**Already fetched by** lists every identity that received the snapshot's content
through the facade, each with a fetch count and a last-fetch time, from
`GET /api/v1/snapshots/{id}/fetchers`. It is only requested for a revoked snapshot.
When nobody fetched it, the panel says so rather than showing an empty list.
This read is privileged — admin or an approver of this marketplace — so the
panel shows an error to a session holding neither.

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

**Delete** calls `DELETE /api/v1/snapshots/{id}` and toasts *Snapshot {id} deleted;
it can be restored*. **Restore** calls `POST /api/v1/snapshots/{id}/restore` and
toasts *Snapshot {id} restored*. Both fire immediately, like every other
mutation in the portal — deletion here is a reversible mark, not a purge.

!!! warning "The restore deadline is the real one"

    Once `purgeAfter` has passed, a compaction run removes the snapshot and its
    quarantine ref permanently and the card disappears. See
    [Snapshot retention](retention.md).

Empty state: "No snapshots yet."

### Audit log

Below the snapshots, the marketplace's slice of the ledger: the entries from
`GET /api/v1/audit` whose marketplace is this one, newest first, with the same
verdict colouring as the [Audit log](#audit-log) page — a blocked verdict reads
red here too. A **See the full ledger** link goes to `/audit`. Empty state:
"Nothing recorded against this marketplace yet."

---

## Snapshot contents

**Route:** `/marketplaces/:name/snapshots/:id/files` · **Heading:** Snapshot
contents

The reviewer's file explorer for one snapshot: exactly the commit it pins. The
same explorer is the **Contents** tab of the [snapshot card](#the-card); this
full-width route renders it on its own, and keeps working for links already
sent.

**The address is the feature.** Approval is a two-person decision
(see [Approving snapshots](../guides/approving-snapshots.md)), and this page is
addressed so the first reviewer can send the second a link to a *file* rather
than directions for finding it. The selected path is in the query string:

```text
/marketplaces/acme/snapshots/41/files?path=plugins/hello/skills/hello/SKILL.md
```

Opening that address restores the same file, with the directories above it
already open. Back and forward walk the files visited. Which directories are
open is not in the address — it is derived from the selection and from what you
have since toggled.

The page takes the whole window, and each pane scrolls on its own; the page
itself does not scroll.

**Left pane — the tree.** The paths of the pinned commit
(`GET /api/v1/snapshots/{id}/files`), nested into real directories, directories
before files, sizes on the right. Paths this snapshot *removes* relative to what
is served are merged in and marked `removed`: a surface for reading a change
that hid the deletions would be the wrong surface.

Above it, a filter over the loaded tree. Its count is stated against the set it
searched — "12 matching of 2000" — and when the listing was cut at its limit the
line says that too, so a search is never read as complete when it cannot be.

**Right pane — the file.** The selected blob
(`GET /api/v1/snapshots/{id}/file?path=`), with a **vs served** toggle for that
one file, read out of `GET /api/v1/snapshots/{id}/diff`. Files and the diff no
longer compete for one box: switching to the diff leaves the tree where it is.

Every bound of these reads is a state this page renders on purpose:

| Condition | What the page says |
| --- | --- |
| Listing cut at 2000 paths | "…, and the listing is cut at its limit", beside the count |
| Blob over 128 KiB | "Truncated: showing the first part of N bytes.", with the first part |
| Binary blob | "Binary file (N bytes) — content is not rendered." |
| Path removed by this snapshot | Marked `removed` in the tree; the pane shows the removal |
| File unchanged vs served | "Unchanged against the served commit." |
| Nothing served yet | No baseline; approving serves all of it |
| No approver role | "You cannot read this snapshot's contents.", naming the role needed |
| Snapshot is not this marketplace's | "Snapshot N is not a snapshot of X." |

Markdown renders inertly — there is no HTML pipeline at all, so HTML embedded in
a hostile file appears as visible text and links are shown but never navigable.
Other text renders preformatted. This is inspection, not execution: nothing
fetched here is ever run, followed or injected as markup, and the page changes
nothing about what the facade serves.

The reads are privileged — admin or an approver of this marketplace — and the
page is gated by the server refusing them, not by the link being hidden.

---

## Vetting (governance)

**Route:** `/vetting` · **Heading:** Vetting

**Administrator-only.** The sidebar offers the entry only to a session holding
the `admin` role, and the page itself renders a stated refusal —
*"This page needs the administrative role."* — to any other session rather than
an empty shell. The server refuses each of the page's reads independently, which
is what actually protects them.

Where [Marketplace detail](#vetting-chain-administrators) governs one
marketplace's chain, this page governs the estate: the chain every marketplace
runs unless it says otherwise, and the ones that say otherwise.

### The default chain

The chain as it applies to **a marketplace with no override of its own** — the
same drawing, the same per-vetter switch, and the same two chain-level controls
as the marketplace card, scoped globally. Fed by
`GET /api/v1/vetting/global-chain` and `GET /api/v1/vetting/global-chain-settings`,
which resolve in the gateway rather than being recomposed in the browser, so this
page cannot disagree with what actually runs. The source a control reports here is
`global` or `default`, never `this marketplace`.

Writing here calls the same endpoints the marketplace card calls with the
`marketplace` field omitted, which is the global setting. A marketplace that
overrides the setting keeps its own; clearing that override is how it comes back.

### Overrides

One row per marketplace whose chain departs from the default, showing what it
departs in — `mode: stop-after-fail`, `order: …`, `secret-scan: off` — and a
**Clear every chain override on _name_** control. Assembled from
`GET /api/v1/vetting/chain-settings`, `GET /api/v1/vetting/vetter-toggles` and
`GET /api/v1/marketplaces`; a stored override whose marketplace is no longer
registered still gets a row, labelled by its id and with its control disabled.

!!! warning "Clearing is not setting the default value"

    An override that happens to equal the default still pins the marketplace: its
    source stays `this marketplace`, so the next change to the default passes it by.
    **Clear override** removes the setting, which is the only thing that puts the
    source back to `global` or `default`. Clearing a marketplace that overrides
    nothing writes nothing and records nothing.

Empty state: *"No marketplace overrides the default chain. Everything in the
estate runs exactly what is above."*

### Bulk edit

Select marketplaces — individually or **All marketplaces** — then choose one
change: *Set the chain mode*, *Set the vetter order*, *Switch a vetter*, or
*Clear overrides*. Selection and every control are ordinary keyboard-operable
controls; the order to apply uses the same named **Move _vetter_ up / down**
buttons the marketplace card uses.

**Review the change** is disabled until a selection exists, with a hint saying
what is missing. It opens a confirm step that states the act in a sentence and
lists every affected marketplace with its **Now** and **After** — stated as the
marketplace's *own* setting (`no mode override` → `mode: stop-after-fail`),
because that is what will be stored.

**Apply to these marketplaces** calls
[`POST /api/v1/vetting/chain-settings/bulk`](api/marketplaces.md#post-vettingchain-settingsbulk).
The result is rendered per marketplace — `applied`, `unchanged`, `failed` with
the server's reason — together with the **correlation id** every ledger entry the
act wrote carries. A response in which anything was refused renders as a failure,
with an error toast and an `alert`; it is never reported as a success.

---

## Audit log

**Route:** `/audit` · **Heading:** Audit log

The ledger and its export surface. Subtitle: "Append-only ledger of every facade
fetch and administrative action, exportable to an external compliance system."

### Export

A **Download ledger (NDJSON)** link pointing at `/api/v1/audit/export` — a plain
same-origin, session-authenticated download, not a fetch through the API client.

### Export sinks

An inline form with **Sink name** and **Target URL** posts to
`POST /api/v1/audit/sinks`. **Add sink** stays disabled until the name matches
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
position is API-only (`PUT /api/v1/audit/sinks/{id}/cursor`).

**Delete** calls `DELETE /api/v1/audit/sinks/{id}`, taking the sink's delivery
channel with it, and toasts *Sink '{name}' deleted*.

Sink deliveries are ordinary webhook deliveries, so their attempts appear on the
[Webhooks](#webhooks) page rather than here.

Empty state: "No export sinks yet."

### Ledger

The table, from `GET /api/v1/audit`, **newest first** — the API answers in ledger
order, so the page sorts it by timestamp descending to open on what just
happened. Any column header re-sorts it.

| Column | Contents |
| --- | --- |
| Status | A verdict badge derived from the entry: a `vetting-completed` row reads from the `outcome=` in its detail, and refusal/violation events (rejected, revoked, refused approval, a re-vetting violation, a blocked private key) read **blocked**. A blocked row is drawn in the destructive colour — the same red the marketplace surfaces use — with a faint tint and a left accent so it is findable; a clear act reads in the accent, a warn one muted. Everything else (a fetch, an ingest, a token event) is neutral and uncoloured. |
| When | The entry timestamp. Sortable. |
| Event | The event name, monospace. Sortable and filterable. |
| Principal | The acting identity. Sortable and filterable. |
| Marketplace | A **link** to that marketplace's [detail page](#marketplace-detail); "—" for an entry not tied to a marketplace. Sortable and filterable. |
| Commit | First 12 characters of the SHA, or "—". Filterable. |
| Detail | The entry's free-text detail. |

The per-column filter boxes sit above the table; each is free-text but offers
**completion** from the values actually present, so you pick rather than type
blind. The event, principal and commit lists are the distinct values in the
loaded rows; the marketplace list draws on the registered marketplaces, so it
completes beyond the rows on screen. Because the table paginates client-side
(25 rows a page) over the JSON ledger, the event/principal/commit suggestions
cover only the rows loaded so far. The status is a **read-only legibility
aid** derived from what the ledger already records — it does not correct the
ledger; the lossy verdict detail and the actor-type on automated vetting
principals are tracked in [#221](https://github.com/skillsgateway/skillsgateway/issues/221).
For a full, resumable pull, use the [NDJSON export](#export) and its cursor.

Empty state: "No fetches recorded yet."

---

## Adoption

**Route:** `/adoption` · **Heading:** Adoption

The [adoption and staleness reports](api/adoption.md), read-only. Subtitle:
"Who fetches what through the facade, aggregated from the append-only ledger,
and which identities are not on the served tip." Both underlying reads require
the auditor role (see [Authorization](#authorization)), so a session without
it sees the page's error state.

### Window and totals

A **Report window** button group — 7, 30 or 90 days, default 30, the active
choice pressed — drives `GET /api/v1/adoption?days=`. Beside it, stat chips:
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
| Last fetch | Most recent, in the reader's locale; the ISO-8601 instant on the tooltip. |
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

An inline form with **Subscriber name**, **Target URL** and **Events**. Events is
a checkbox list built from `GET /api/v1/webhooks/events`, so the portal offers exactly
the names this gateway emits rather than asking for them: **All events** ticks or
clears every one, and the box above the list narrows what is shown without
changing what is selected. Every event is ticked by default, and that state submits
`*` rather than an enumeration — so a subscriber registered today also receives
events added to the gateway later.

**Add subscriber** stays disabled until the name matches `^[a-z0-9][a-z0-9_-]*$`,
the target URL parses with a scheme, and at least one event is ticked; only then is
`POST /api/v1/webhooks` called. The scheme allowlist remains server-side and surfaces
as an error toast.

The response opens a show-once dialog — "This signing secret is shown exactly
once — copy it now." — with the `whsec_…` value in a code block and a clipboard
button that flips to a checkmark for two seconds, the same pattern as
[Access tokens](#access-tokens).

### Subscribers

| Column | Contents |
| --- | --- |
| Name | The subscriber name. |
| Target URL | The registered endpoint. |
| Events | The filter, rendered as a chip; `*` displays as "all events". A name absent from the served registry is flagged with an **unknown event** badge, whose tooltip names it. |
| Status | `enabled` (primary badge) or `disabled` (secondary). |
| Actions | **Delete**, which fires immediately. |

**Delete** calls `DELETE /api/v1/webhooks/{id}` and toasts *Subscriber '{name}'
deleted*. It removes the delivery history with the subscriber.

Empty state: "No subscribers yet."

### Delivery attempts

From `GET /api/v1/webhooks/deliveries` — the operator's view of a failing
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
`POST /api/v1/tokens`. **Create token** stays disabled until the name field holds a
non-blank value — the name is trimmed before it is sent. The response opens a
show-once dialog — "This value is shown
exactly once — copy it now. Only a hash is stored." — with the token in a code
block and a clipboard button that flips to a checkmark for two seconds.

| Column | Contents |
| --- | --- |
| Name | The name you gave it. |
| Created | Creation timestamp. |
| Last used | How long ago the token last authenticated successfully, or **never**. Hover for the exact instant. |
| Status | `active` (primary badge) or `revoked` (destructive). |
| Actions | **Revoke**, shown only while active. |

**Last used** is the column to read before revoking: it separates a token
something still authenticates with from one nobody has used. It shows the
recency rather than the instant, because recency is the question; the exact time
stays on the hover title, as on every other timestamp in the portal. The gateway
records it at most once a minute, so it can be up to a minute behind, and a
refused authentication never moves it — see
[`lastUsedAt`](api/tokens.md#lastusedat) for what a **never** does and does not
prove.

**Revoke** calls `DELETE /api/v1/tokens/{id}` and toasts *Token '{name}' revoked*.
It fires immediately. Revocation is recorded rather than deleted: the row stays
with a `revoked` badge.

Empty state: "No tokens yet."

See [Consuming approved skills](../guides/consuming-skills.md) for using a token
with a git client.
