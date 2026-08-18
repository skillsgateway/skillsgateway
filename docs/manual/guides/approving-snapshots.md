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

If ingestion flagged the snapshot — an external plugin source, for instance,
which is rejected fail-closed in the current scope — the reason is on the
snapshot row and rendered in the portal as destructive text.

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
| 409 | The snapshot is neither `held` nor `revoked`, its effective vetting outcome is blocked (see `uncoveredFindings`), a [policy rule](policy-rules.md) denied it, or it has not yet cleared the [minimum release age](#waiting-out-the-minimum-release-age). |

!!! warning "An approved snapshot cannot be re-decided"

    Approving is not reversible by hand. A snapshot that is `approved` or
    `rejected` returns 409 from both endpoints, and there is no un-approve.

    To move a marketplace back to earlier content, re-ingest the desired
    upstream commit and approve that snapshot. Approving an older one is not a
    rollback mechanism.

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

## Rejection is not deletion

The rejected snapshot stays as evidence — it is the record that someone looked
at this exact upstream commit and said no.

## Who may approve

Anyone with a portal session. There is no role model: any authenticated session
can register, ingest, approve and reject.

Until admin/reviewer separation lands, **access to the portal is the reviewer
privilege**. Grant it through your identity provider and treat the portal as a
restricted application.

## After approval

The content is live. Verify it and point clients at it —
[Consuming approved skills](consuming-skills.md).

If the chain objected and you accepted the risk, see
[Waiving a vetting finding](waiving-findings.md) for what the acceptance covers
and when it lapses.
