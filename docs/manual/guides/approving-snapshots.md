# Approving and rejecting snapshots

Approval is the gate. It is the only way content reaches a client, and today it
is a human pressing a button.

That is a smaller claim than it sounds. The security property does not come from
the sophistication of the vetting; it comes from the fact that **nothing is
served until someone decides**, and that the decision is recorded against an
immutable SHA.

## Reviewing

A held snapshot gives you four things.

### The content itself

The **Preview files** toggle on the marketplace detail page opens the pinned
commit's actual file tree, each file rendered inertly (Markdown without any
HTML interpretation, binary files described rather than shown), and — the part
that usually decides a successor snapshot — **Diff vs served**: exactly which
paths were added, modified or removed against the commit consumers are
currently receiving, with per-file text diffs. A snapshot of a marketplace
serving nothing shows every path as new: approving it serves all of it.

The same reads exist on the API (`GET /api/snapshots/{id}/files`, `.../file`,
`.../diff` — see
[the API reference](../reference/api/marketplaces.md#snapshot-preview)). With
[role enforcement](delegated-administration.md) enabled they require admin or
an approver grant for the marketplace, because they return held quarantine
content.

### Contents

`GET /api/snapshots/{id}/content` enumerates what the snapshot declares — each
plugin with its name, `source` and description, and the skills found under each.
In the portal this is the **Show contents** toggle on the marketplace detail
page.

```console
$ curl localhost:8080/api/snapshots/1/content
```

```json
{"snapshotId":1,"sha":"3f9c2ab...","state":"held",
 "plugins":[{"name":"acme-tools","description":"...","source":"./plugins/acme-tools",
             "skills":[{"name":"deploy","path":"skills/deploy/SKILL.md"}]}]}
```

This works on `held` snapshots by design — reviewing must not require serving.

### What it changes

The inventory says what the snapshot ships; `GET
/api/snapshots/{id}/content-diff` says what approving it would add to what you
already approved. Every plugin and skill is marked `added`, `removed`,
`changed`, `moved` or `unchanged` against the marketplace's last approved
snapshot, and a skill counts as changed when anything under its directory
differs — not only its `SKILL.md`.

```console
$ curl localhost:8080/api/snapshots/1/content-diff
```

On the tenth snapshot of a large marketplace this is the read worth starting
from: it is the difference between reviewing two new skills and re-reading
forty. In the portal it is the second half of the **Show contents** panel. See
[the API reference](../reference/api/marketplaces.md#get-snapshotsidcontent-diff).

### Provenance

Where it came from and who has touched it:

```console
$ curl localhost:8080/api/snapshots/1/provenance
```

```json
{"snapshotId":1,"marketplace":"acme","upstreamUrl":"https://github.com/acme/skills.git",
 "upstreamSha":"3f9c2ab...","state":"held","violation":null,
 "ingestedAt":"...","decidedBy":null,"decidedAt":null}
```

### Violations

If ingestion flagged the snapshot — an external plugin source, for instance —
the reason is on the snapshot row and rendered in the portal as destructive text.

An external source produces one of two violations, and they mean different
things. *"has a non-local source"* means the gateway will not admit it: either
external sources are not enabled, or the type, host or scheme is outside what is
configured, or the type (`npm`, `archive`) is never admissible. *"admits but
cannot yet resolve"* means the source passed admission and the gateway simply
cannot fetch and rewrite it yet — a snapshot is held only when every source it
declares resolves inside the snapshot the gateway serves. The first is a
configuration question; the second is not, and neither snapshot can be approved.

### Vetting verdicts

Before anything else, read what the vetting chain concluded. The snapshot card
on the marketplace detail page — and the approve dialog itself — shows the chain
outcome and, per connector, its verdict and every finding with the file and line
it came from.

A **blocked** outcome means at least one connector failed, errored or has not
answered, or that the chain never ran for this snapshot at all.

!!! warning "A clear outcome is not a clean bill of health"

    The built-in connectors are pattern matchers. They catch known credential
    shapes and known prompt-injection markers; a paraphrased instruction or an
    unshaped secret goes straight past them. Treat `clear` as "nothing known
    matched", and keep reading the content. The limits of each connector are
    listed under *What these connectors can and cannot see* next to the
    verdicts, and in [Vetting — the connector chain](../concepts/vetting.md).

## What to look for

The threats that matter here are the ones no scanner catches:

- **Instructions, not just code.** `SKILL.md`, commands and agent definitions
  are read by an agent as instructions. Look for anything directing the agent
  toward credentials, network calls, or files outside the skill's stated
  purpose.
- **Hooks and MCP servers.** These execute without the user ever invoking a
  skill. A plugin that registers them deserves more scrutiny than a
  markdown-only skill.
- **The diff, on updates.** A skill that grows a `scripts/` directory has
  changed category, and that transition is itself worth a closer look.

## Deciding

=== "Portal"

    On the **Marketplaces** page, held snapshots show **Approve** and **Reject**
    buttons in their row. **Reject** fires immediately. **Approve** opens a
    dialog showing the vetting verdicts; if the chain blocked the snapshot, the
    confirm button stays disabled until every blocking finding is waived from
    that same dialog.

=== "API"

    ```console
    $ curl -X POST localhost:8080/api/snapshots/1/approve
    $ curl -X POST localhost:8080/api/snapshots/1/reject
    ```

    Neither takes a request body. A snapshot the vetting chain blocked is
    refused with 409 until each blocking finding is covered by an active
    [waiver](waiving-findings.md); the problem document lists them in
    `uncoveredFindings`.

**Approve** publishes: the pinned `refs/snapshots/{sha}` is fetched from
quarantine into the published repository and `refs/heads/main` is force-updated
to that SHA. From the next client fetch onward, this is what the marketplace
serves.

**Reject** marks the snapshot `rejected` and touches no repository. Whatever was
already approved keeps serving.

| Status | Cause |
| --- | --- |
| 200 | Decided. |
| 404 | Unknown snapshot. |
| 409 | The snapshot is neither `held` nor `revoked`, its effective vetting outcome is blocked (see `uncoveredFindings`), a [policy rule](policy-rules.md) denied it, it has not yet cleared the [minimum release age](#waiting-out-the-minimum-release-age), or an enforcing [four-eyes rule](#separation-of-duties) refused it (see `conflicts`). |

!!! warning "An approved snapshot cannot be re-decided"

    Approving is not reversible by hand. A snapshot that is `approved` or
    `rejected` returns 409 from both endpoints, and there is no un-approve.

    To move a marketplace back to earlier content, re-ingest the desired
    upstream commit and approve that snapshot. Approving an older one is not a
    rollback mechanism.

## Separation of duties

An approval is worth what the independence of the approver is worth. The
gateway therefore checks, at approval time, whether the reviewer is on the
snapshot's **supply** side — whether they, personally, are any of:

| Conflict | You |
| --- | --- |
| `registered-by` | registered the marketplace this snapshot came from |
| `ingested-by` | triggered the ingestion that pinned it |
| `waiver-author` | wrote a [waiver](waiving-findings.md) this approval relies on |

The third is not a technicality. Waiving a finding and then approving past your
own waiver is one decision wearing two hats, and it is the route around the
other two: a reviewer refused for having ingested a snapshot could otherwise
accept the objection on a fresh copy and approve that instead.

The automated sync triggers never conflict. A snapshot the polling sweep or a
forge webhook brought in records `scheduler` or `webhook` as its ingestion
actor, and neither is a person whose independence is being protected — so an
estate on [automatic sync](upstream-sync.md) is approvable by anyone the role
model allows. Nor does an unrecorded actor conflict: marketplaces and snapshots
that predate this release carry none, so the rule tightens only from here on.

What a conflict *does* is a deployment decision,
[`skills-gateway.approval.four-eyes.mode`](../reference/configuration.md#separation-of-duties-four-eyes):

- **`warn`, the default.** The approval proceeds and the conflict is written to
  the audit ledger. This is what keeps a single-administrator deployment — a
  first evaluation, a small team — working: there is nobody else to ask, and
  refusing would only mean nothing could ever be published. The record is what
  makes that visible rather than silent.
- **`enforce`.** The approval is refused with `409`, and the snapshot stays
  `held` with nothing published. Somebody else has to decide.

There is deliberately no way to switch detection off. A conflict reaches the
ledger in both modes as a `four-eyes-conflict` entry naming the acting identity,
the snapshot, the mode, whether the approval proceeded, and each conflicting
act — which is also how you size up `enforce` before turning it on: run `warn`,
then read how many approvals would have been refused, and by whom.

Neither mode gates **Reject**. Refusing suspicious content quickly must never
wait for a second pair of eyes.

You can see where you stand before deciding. The portal's approve dialog says
which acts conflict — as a warning under `warn`, and with the confirm button
shut under `enforce` — and the API answers the same question without deciding
anything:

```console
$ curl localhost:8080/api/snapshots/1/four-eyes
```

```json
{"mode":"ENFORCE","refused":true,
 "conflicts":[{"role":"registered-by","principal":"dana","waiverId":null},
              {"role":"ingested-by","principal":"dana","waiverId":null}]}
```

A refused approval answers with the same list in its problem document, under
`conflicts`, alongside the `configKey` that imposed it.

!!! warning "`enforce` needs a second approver per marketplace"

    Under `enforce`, a marketplace whose only approver also registered it or
    ingests its content has nobody left who may publish it. Give every
    marketplace that needs deciding a second identity with approval rights —
    a second admin, or an approver scoped to it under
    [delegated administration](delegated-administration.md) — before switching.

## Waiting out the minimum release age

If your deployment configures
[`skills-gateway.vetting.minimum-release-age`](../reference/configuration.md#minimum-release-age),
a snapshot cannot be approved until the gateway has been holding its commit for
that long. The portal shows the approve control disabled and reading **Eligible
in 2d 4h**; the API answers `409` with the setting, the current age and the time
remaining, and `GET /api/snapshots/{id}/release-age` answers the same question
without attempting a decision.

The window exists for a threat no scanner covers: a compromised release is
usually noticed by the wider world — often by the project's own community —
within hours of being pushed, and it is frequently pulled again just as fast.
Adopting a commit the moment it lands forfeits that detection entirely.

Three things about it are worth knowing before it surprises you.

- **Nothing has to happen for the wait to end.** The age is compared on each
  approval request, so the snapshot becomes approvable by itself. There is no
  queue to re-run and no state to clear.
- **The clock is this gateway's first sighting of the commit**, not the
  commit's date, and re-ingesting the same commit does not restart it. A
  backdated or re-pushed commit gets no credit.
- **There is no override.** The wait applies to a newly registered
  marketplace's very first snapshot too. Shipping something before the window
  elapses means changing the configuration, which is a deployment someone
  reviews — deliberately a heavier act than clicking past a dialog.

Both outcomes are on the [audit ledger](../concepts/snapshots-and-ledger.md): an
approval that went through records how long the commit had been held
(`snapshot-approved`, detail `ingestion-age=…`), and one the window turned away
is its own entry (`snapshot-approval-refused`). What the wait was worth is
therefore answerable from the ledger alone.

Reviewing is unaffected: the contents, the provenance and the vetting verdicts
are all readable during the wait, so the waivers a blocked snapshot needs can be
recorded while the window runs down rather than after it.

## Re-approving a revoked snapshot

A snapshot that [continuous re-vetting](re-vetting.md) revoked is decidable
again, and the route back is this same **Approve** — deliberately, because a
retraction the gateway made without a person has to be answerable by one.

Nothing about the gate is relaxed for it. The effective vetting outcome is
evaluated exactly as for a held snapshot, so the finding that caused the
violation has to be waived (or fixed by re-ingesting a corrected upstream
commit) before the approval succeeds. What the decision records is fresh: a new
reviewer, a new timestamp, and the revocation marks cleared. What the snapshot
was revoked for stays in the audit ledger.

Rejecting it instead is the terminal answer, and the right one when the finding
is not something anyone intends to accept.

## When an approval fails

An approval publishes by moving the marketplace's served references onto the
snapshot. That is a single all-or-nothing transition, and it can be refused — by
a competing writer holding the reference, by the storage underneath it, or, on
the object-store backend with more than one replica, by an ordinary lost
compare-and-swap.

A refused publication **fails the approval**. Nothing is published, the snapshot
stays exactly as it was — held, or revoked, with its revocation intact — the
audit ledger records no approval, and no lifecycle event is emitted. Retry the
approval; there is nothing to clean up first.

!!! danger "Do not treat a failed approval as a published one"

    Earlier versions reported such an approval as successful: the snapshot was
    recorded as approved, the ledger said so, and subscribers were notified, while
    the facade went on serving the previous tip. If you are looking at an estate
    where the database and the facade disagree about what is served, that is the
    cause, and re-approving the affected snapshot is the fix.

## Rejection is not deletion

The rejected snapshot stays as evidence — it is the record that someone looked
at this exact upstream commit and said no.

## Who may approve

Two independent questions, answered in this order.

**May this principal approve here at all?** That is
[delegated administration](delegated-administration.md): with role enforcement
enabled, an admin or an approver scoped to the marketplace. With it disabled —
the default — any authenticated session can register, ingest, approve and
reject, and access to the portal is itself the reviewer privilege.

**May this principal approve *this* snapshot?** That is the four-eyes rule
above, and it is a different question: it is about what the reviewer already did
to the content in front of them, not about what they are permitted to do in
general. A principal can hold every role there is and still be the wrong person
to approve one particular snapshot.

## After approval

The content is live. Verify it and point clients at it —
[Consuming approved skills](consuming-skills.md).

If the chain objected and you accepted the risk, see
[Waiving a vetting finding](waiving-findings.md) for what the acceptance covers
and when it lapses.
