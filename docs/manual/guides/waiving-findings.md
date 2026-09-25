# Waiving a vetting finding

The [vetting chain](../concepts/vetting.md) blocks approval whenever a vetter
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

- A snapshot whose vetting outcome is blocked. `GET /api/v1/snapshots/{id}/vetting`
  shows the verdicts, or open the approve dialog in the portal.
- A portal session, or — for the API — a session cookie and the CSRF token that
  goes with it ([REST API](../reference/api/index.md#conventions)).

## 1. Find out exactly what is blocking

Ask for the approval and read the refusal — it lists the work:

```console
$ curl -sS -X POST localhost:8080/api/v1/snapshots/1/approve | jq
{
  "status": 409,
  "title": "Vetting chain blocked this snapshot",
  "detail": "snapshot 1 cannot be approved: the vetters secret-scan did not clear it. Uncovered findings: aws-access-key-id at plugins/hello/DEPLOY.md:5, plugins/copy/DEPLOY.md:5. Record a scoped, expiring waiver for each blocking finding group — with a justification and an expiry — and approve again.",
  "blockingVetters": ["secret-scan"],
  "uncoveredFindings": [
    {
      "vetter": "secret-scan",
      "ruleId": "aws-access-key-id",
      "location": "plugins/hello/DEPLOY.md:5",
      "locations": ["plugins/hello/DEPLOY.md:5", "plugins/copy/DEPLOY.md:5"],
      "content": "3b18e512dba79e4c8300dd08aeb37f8e728b8dad",
      "line": 5,
      "severity": "critical",
      "message": "an AWS access key id is committed in this file"
    }
  ]
}
```

`uncoveredFindings` is the complete list, with one entry per **finding group**.
A group is the findings of one rule on one line of identical content, which is
the same git blob. A file vendored into two plugins is therefore one entry with
both locations. The refusal spells out the first ten locations of each group and
counts the rest. Every entry needs a waiver before the approval will succeed.
Covering only some of them changes nothing.

!!! warning "Read the file before you accept the finding"

    The vetters match shapes, so a finding is a place to look, not a verdict on
    its own. Open the location and confirm what is actually there. Accepting a
    real credential is how it reaches every developer who installs the skill.

## 2. Record the waiver

=== "Portal"

    In the approve dialog, each blocking group carries a button: **Waive
    finding** for a single location, or **Waive all N locations** for a group.
    The button opens a small form beside the group, with these controls:

    - **Scope** — one of the following:
        - *These N identical copies, in this snapshot* (or *This finding, in
          this snapshot*). This is the default, and it covers the group and
          nothing else.
        - *Every {rule} finding in this snapshot*.
        - For a single location, *Every {rule} finding under {path}, in later
          snapshots too*.
    - **Expires on** — defaults to 30 days out.
    - **Justification** — required; the confirm button stays disabled without it.

    **Record waiver** applies it immediately. The group is struck through and
    badged with who accepted it and until when. The outcome badge changes to
    **vetting clear with waivers** once nothing is left uncovered.

=== "API"

    ```console
    $ curl -X POST localhost:8080/api/v1/snapshots/1/waivers \
        -H 'Content-Type: application/json' \
        -d '{
              "ruleId": "aws-access-key-id",
              "scope": "SNAPSHOT",
              "justification": "documented dummy key in the fixtures directory",
              "expiresAt": "2026-09-30T23:59:59Z"
            }'
    ```

    To accept one finding group and nothing else, name its `content` and
    `line` as the vetting report gives them. A group waiver is always
    `SNAPSHOT`-scoped:

    ```console
    $ curl -X POST localhost:8080/api/v1/snapshots/1/waivers \
        -H 'Content-Type: application/json' \
        -d '{
              "ruleId": "aws-access-key-id",
              "scope": "SNAPSHOT",
              "content": "3b18e512dba79e4c8300dd08aeb37f8e728b8dad",
              "line": 5,
              "justification": "documented dummy key, vendored into two plugins",
              "expiresAt": "2026-09-30T23:59:59Z"
            }'
    ```

    For a waiver that should survive re-ingestion, use `PATH` scope and name the
    path:

    ```console
    $ curl -X POST localhost:8080/api/v1/snapshots/1/waivers \
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

| | Group (`SNAPSHOT` + `content`) | `SNAPSHOT` | `PATH` |
| --- | --- | --- | --- |
| Covers | this rule on this line of this blob, at every path it occurs, in this commit | this rule anywhere in this commit | this rule at this path and everything under it |
| Survives re-ingestion | no | no | yes |
| Covers content added later | no | no | **yes** |
| Use it when | you read *this* content | you read every finding of the rule in this commit | the same benign pattern will recur at this path |

The group is the portal's default because it is the tightest. It covers exactly
the content you read, wherever that content was copied, and nothing else. A new
file with the same rule, a different line of the same file, and the same bytes
in the next commit are all outside it. A snapshot waiver on the rule alone is
wider: it accepts every finding of that rule in the commit, including ones in
files you have not opened. Reach for `PATH` when re-approving the same finding
on every ingestion would be busywork. Give it a short expiry, since it will
also cover files that do not exist yet.

## 4. Approve

With every blocking finding covered, the approval goes through with no special
request and no flags:

```console
$ curl -X POST localhost:8080/api/v1/snapshots/1/approve
```

Each waiver that was in force is written to the ledger as `waiver-applied`, with
the rule, the location, the approver and the expiry.

## Plugin-name collisions

The approval gate also refuses a snapshot that introduces a plugin name looking
like one another marketplace already serves
([how that is decided](../concepts/vetting.md#not-a-vetter-either-plugin-names-already-in-use)).
It is not a vetter's finding, but it is accepted the same way: a waiver on rule
**`plugin-name-collision`**, located at the manifest line that declares the name.

Waive it only when you can account for the name — a fork or a vendored copy of
the incumbent — never because the refusal is in the way. The incumbent is named
in the refusal and in the approve dialog.

- **Use snapshot scope.** It accepts every collision that commit introduces.
  Because a name already carried by an approved snapshot of the same marketplace
  is not checked again, you accept each name once, not at every ingestion.
- **Avoid path scope.** The location is the manifest, so a path waiver on
  `.claude-plugin/marketplace.json` accepts every lookalike the marketplace
  introduces until it expires. The API accepts it; the portal does not offer it.
- **The administrator's vetting override does not lift it.**

## Reviewing and withdrawing waivers

List what a marketplace has accepted — active and lapsed alike:

```console
$ curl -sS localhost:8080/api/v1/marketplaces/corp-marketplace/waivers | jq
```

A lapsed or revoked waiver is kept and returned with `"active": false`: the
record of what was once accepted is part of the audit trail.

Withdraw one when the reason no longer holds:

```console
$ curl -X DELETE localhost:8080/api/v1/waivers/3
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

- [Vetting — the vetter chain](../concepts/vetting.md#waivers-accepted-risks-with-a-scope-and-an-expiry)
  — the model and the effective-outcome rule.
- [Approving and rejecting snapshots](approving-snapshots.md) — the decision the
  waiver unblocks.
- [Marketplaces and snapshots API](../reference/api/marketplaces.md#vetting-waivers)
  — the endpoint contract.
