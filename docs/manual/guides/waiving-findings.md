# Waiving a vetting finding

The [vetting chain](../concepts/vetting.md) blocks approval whenever a connector
objects. Sometimes the objection is one you have looked at and decided to accept
— a documented dummy credential in a fixtures directory, a phrase that trips the
prompt-injection heuristics inside an example. A **waiver** is how you record
that decision so that the gate lets exactly that finding through, and nothing
else.

A waiver always names four things, and none of them is optional:

- the **rule** it accepts (`aws-access-key-id`), not "this snapshot";
- the **scope** it applies to — this commit, or this path in the marketplace;
- a **justification** in your own words;
- an **expiry**. There are no unlimited waivers.

The identity accepting the risk is your session, and it is recorded with the
waiver and in the audit ledger.

## Before you start

- A snapshot whose vetting outcome is blocked. `GET /api/snapshots/{id}/vetting`
  shows the verdicts, or open the approve dialog in the portal.
- A portal session, or a session cookie for the API.

## 1. Find out exactly what is blocking

Ask for the approval and read the refusal — it lists the work:

```console
$ curl -sS -X POST localhost:8080/api/snapshots/1/approve | jq
{
  "status": 409,
  "title": "Vetting chain blocked this snapshot",
  "detail": "snapshot 1 cannot be approved: the vetting connectors secret-scan did not clear it. Uncovered findings: aws-access-key-id at plugins/hello/DEPLOY.md:5. Record a scoped, expiring waiver for each blocking finding — with a justification and an expiry — and approve again.",
  "blockingConnectors": ["secret-scan"],
  "uncoveredFindings": [
    {
      "connector": "secret-scan",
      "ruleId": "aws-access-key-id",
      "location": "plugins/hello/DEPLOY.md:5",
      "severity": "CRITICAL",
      "message": "an AWS access key id is committed in this file"
    }
  ]
}
```

`uncoveredFindings` is the complete list. Every entry needs a waiver before the
approval will succeed — covering some of them changes nothing.

!!! warning "Read the file before you accept the finding"

    The scanners match shapes, so a finding is a place to look, not a verdict on
    its own. Open the location and confirm what is actually there. Accepting a
    real credential is how it reaches every developer who installs the skill.

## 2. Record the waiver

=== "Portal"

    In the approve dialog, each blocking finding carries a **Waive…** button.
    It opens a small form beside the finding itself:

    - **Scope** — *This snapshot only* (the default) or *This path in the
      marketplace*.
    - **Expires on** — defaults to 30 days out.
    - **Justification** — required; the confirm button stays disabled without it.

    **Record waiver** applies it immediately. The finding is struck through and
    badged with who accepted it and until when, and the outcome badge changes to
    **vetting clear with waivers** once nothing is left uncovered.

=== "API"

    ```console
    $ curl -X POST localhost:8080/api/snapshots/1/waivers \
        -H 'Content-Type: application/json' \
        -d '{
              "ruleId": "aws-access-key-id",
              "scope": "SNAPSHOT",
              "justification": "documented dummy key in the fixtures directory",
              "expiresAt": "2026-09-30T23:59:59Z"
            }'
    ```

    For a waiver that should survive re-ingestion, use `PATH` scope and name the
    path:

    ```console
    $ curl -X POST localhost:8080/api/snapshots/1/waivers \
        -H 'Content-Type: application/json' \
        -d '{
              "ruleId": "instruction-override",
              "scope": "PATH",
              "path": "plugins/hello/examples",
              "justification": "example file demonstrating an attack, reviewed",
              "expiresAt": "2026-09-30T23:59:59Z"
            }'
    ```

## 3. Choose the scope deliberately

| | `SNAPSHOT` | `PATH` |
| --- | --- | --- |
| Covers | exactly this commit | this path and everything under it |
| Survives re-ingestion | no | yes |
| Covers content added later | no | **yes** |
| Use it when | you accepted *this* content | the same benign pattern will recur at this path |

`SNAPSHOT` is the default because it is the tighter one: it dies with the SHA,
so the next ingestion asks again. Reach for `PATH` when re-approving the same
finding on every ingestion would be busywork — and give it a short expiry, since
it will also cover files that do not exist yet.

## 4. Approve

With every blocking finding covered, the approval goes through with no special
request and no flags:

```console
$ curl -X POST localhost:8080/api/snapshots/1/approve
```

Each waiver that was in force is written to the ledger as `waiver-applied`, with
the rule, the location, the approver and the expiry.

## Reviewing and withdrawing waivers

List what a marketplace has accepted — active and lapsed alike:

```console
$ curl -sS localhost:8080/api/marketplaces/corp-marketplace/waivers | jq
```

A lapsed or revoked waiver is kept and returned with `"active": false`: the
record of what was once accepted is part of the audit trail.

Withdraw one when the reason no longer holds:

```console
$ curl -X DELETE localhost:8080/api/waivers/3
```

Revocation takes effect on the next read. A snapshot that was cleared only by
that waiver reads as blocked again immediately.

## What expiry does, and what it does not

When a waiver's expiry passes, it stops suppressing its finding on the very next
evaluation — no scheduled job is involved. The snapshot's effective outcome
reverts to blocked and a future approval needs a fresh acceptance.

!!! warning "Expiry does not un-publish"

    A snapshot approved while the waiver was active keeps serving after it
    lapses. Expiry re-closes the *gate*, it does not retract content from the
    facade. To stop serving something, approve a later snapshot or remove the
    marketplace.

## Related

- [Vetting — the connector chain](../concepts/vetting.md#waivers-accepted-risks-with-a-scope-and-an-expiry)
  — the model and the effective-outcome rule.
- [Approving and rejecting snapshots](approving-snapshots.md) — the decision the
  waiver unblocks.
- [Marketplaces and snapshots API](../reference/api/marketplaces.md#vetting-waivers)
  — the endpoint contract.
