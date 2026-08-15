# Approving and rejecting snapshots

Approval is the gate. It is the only way content reaches a client, and today it
is a human pressing a button.

That is a smaller claim than it sounds. The security property does not come from
the sophistication of the vetting; it comes from the fact that **nothing is
served until someone decides**, and that the decision is recorded against an
immutable SHA.

## Reviewing

A held snapshot gives you three things.

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
| 409 | The snapshot is not `held`, or its effective vetting outcome is blocked (see `uncoveredFindings`). |

!!! warning "Decisions are one-way"

    A snapshot that is not `held` cannot be approved or rejected. There is no
    revocation state and no re-decision — the state machine is
    `held → approved | rejected`, once.

    To move a marketplace back to earlier content, approve the earlier snapshot
    is *not* possible either. Plan rollbacks by re-ingesting the desired
    upstream commit.

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
