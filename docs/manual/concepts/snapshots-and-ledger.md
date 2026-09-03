# Snapshots and the audit ledger

Two objects carry the product's guarantees: the **snapshot**, which makes
content immutable and attributable, and the **ledger**, which makes access
answerable.

## Marketplace

A *marketplace* is a registered upstream git repository — a name, a clone URL,
and best-effort forge metadata (forge, project, description, last upstream
update) captured at registration.

The name must match `^[a-z0-9][a-z0-9_-]*$`. It is the identity of the
marketplace everywhere: the portal route, the API path, and the facade URL
`/git/{name}`.

Registration does **not** fetch anything. It only establishes that this URL is
one the gateway is willing to talk to.

## Snapshot

A *snapshot* is one upstream commit, captured at one moment, with a vetting
decision attached. It is the unit of review, approval, serving, audit and
retention.

| Field | Meaning |
| --- | --- |
| `sha` | The 40-hex commit the gateway serves. This is the pin: the upstream commit for a local-only manifest, and the synthesised composite for one with resolved external sources (see below). |
| `state` | `held`, `approved`, `rejected`, or `revoked`. Set at ingestion and changed only by a decision or by an enforced re-vetting violation. |
| `violation` | Why ingestion flagged the snapshot, or why re-vetting revoked it. Cleared by a fresh decision. |
| `decidedBy` / `decidedAt` | The principal who decided, and when. Survives a revocation. |
| `revokedBy` / `revokedAt` | The identity that revoked it, and when. Cleared by a fresh decision. |
| `createdAt` | When the gateway ingested it. |

```mermaid
stateDiagram-v2
    [*] --> held: ingest
    held --> approved: approve (publishes)
    held --> rejected: reject
    approved --> revoked: enforced re-vetting violation<br/>(unpublishes)
    revoked --> approved: fresh approve decision<br/>(re-publishes)
    revoked --> rejected: reject
    approved --> [*]: serving on refs/heads/main
    rejected --> [*]: never served
    revoked --> [*]: served once, not any more
```

The state machine stays small, and every edge is deliberate.

`approved → revoked` is the only transition the gateway makes without a person,
and only when [continuous re-vetting](../guides/re-vetting.md) is configured to
enforce. It removes the published refs; it does not touch quarantine, so the
content is still there to be re-reviewed.

`revoked → approved` is the way back, and it is the *ordinary* approve endpoint —
no un-revoke, no undo. That means the violation must be waived or fixed first,
because the same effective-vetting gate applies, and it means the return is a
recorded decision with a named reviewer.

Everything else is refused with 409: an approved snapshot cannot be re-decided,
and a rejected one cannot be approved.

!!! note "Deletion is not a state"

    Retention marks a snapshot deleted without touching `state` — a deleted
    snapshot is still whichever of the four it was. See
    [Snapshot retention](../reference/retention.md).

### Why SHA pinning is the point

Git refs are mutable; commit SHAs are not. Everything the gateway stores and
serves is keyed by the snapshot's SHA:

- Quarantine holds `refs/snapshots/{sha}` — one immutable ref per snapshot.
- Approval force-updates the published `refs/heads/main` to *that exact SHA*.
  The published ref moves only because a human approved a specific commit.
- The ledger records the SHA on every fetch.

The consequence: "what exactly ran on that laptop" reduces to a ledger lookup,
and an upstream force-push cannot change the answer retroactively.

### Composite snapshots

For a local-only marketplace — every marketplace, under the shipped
configuration — the snapshot's SHA *is* the upstream commit, and there is nothing
more to say.

When [external plugin sources](../reference/configuration.md#ingestion-external-plugin-sources)
are enabled and a manifest declares one, the snapshot is instead a **synthesised
commit** the gateway assembles at ingestion:

- the upstream tree, unchanged;
- each resolved external plugin's content under `_plugins/<plugin name>/`;
- `.claude-plugin/marketplace.json` rewritten so that plugin's `source` is
  `./_plugins/<plugin name>`;
- **the upstream commit as its parent.**

That commit is the snapshot. It is what vetting opens, what a reviewer approves,
what the facade serves, what retention anchors on and what the ledger records —
so nothing downstream of ingestion learns that external content exists, because
the resolved content is not metadata beside the snapshot: it *is* the snapshot.

The parent is what makes the transformation checkable by someone other than the
gateway. The manifest as upstream declared it stays byte-exact and reachable
from the served SHA, so

```console
git diff <parent> <snapshot sha>
```

is the whole of what the gateway changed, and the commit message names the
upstream commit, every source with the commit it resolved to, and the version of
the transformation that produced it.

Identity follows from the inputs: the same upstream commit, the same resolved
external commits and the same transformation give the same SHA, which is what
makes re-ingesting unchanged content idempotent. An external repository moving on
therefore produces a *different* snapshot, held for its own approval — the same
rug-pull protection the upstream commit already gets, extended to the
repositories the manifest points at.

### What a snapshot contains

`GET /api/snapshots/{id}/content` parses the captured commit and lists what it
declares: each plugin with its name, `source` and description, and the skills
found under each. A resolved external plugin appears like any other, with its
`source` inside the snapshot, because by the time anything reads the snapshot
that is what it is.

This is the review surface, and it works on `held` snapshots — reviewing a
snapshot must not require serving it.

## The audit ledger

The ledger is one PostgreSQL table, `fetch_log`, and it is **append-only**: no
code path in the product issues an `UPDATE` or `DELETE` against it. Retention
does not compact it. That is what makes it usable as compliance evidence rather
than as an operational log.

| Column | Meaning |
| --- | --- |
| `id` | `BIGSERIAL`. The ordering key. |
| `ts` | When the entry was appended. |
| `source` | The client address for a fetch; the literal `admin` for an administrative action. |
| `principal` | The PAT principal for a fetch, the OIDC principal for an admin action. |
| `marketplace` | The marketplace name, or `-` when not marketplace-scoped. |
| `event` | What happened. |
| `ref` | The ref involved, when there is one. For a fetch, a ref the facade advertised for that request. |
| `sha` | The commit involved, when there is one. |

### What gets recorded

**Facade fetches** — `info-refs` when a client asks what refs exist, carrying
the resolved `main` SHA; and one `upload-pack` entry per wanted object when the
packfile is served, naming the advertised ref that object resolves to. A
superseded snapshot stays fetchable by name, so a fetch of one is distinguishable
in the ledger from a clone of the tip — see
[Auditing](../reference/git-facade.md#auditing) for the exact rule and its one
unavoidable ambiguity. Negotiation rounds are not recorded.

**Administrative actions** — registration, ingestion, approve and reject, each
carrying the acting OIDC principal.

### Reading it

`GET /api/audit` returns the whole table, and the portal's
[Audit log page](../reference/portal.md#audit-log) renders it. There is no
filtering, search or paging: it is a recent-activity view.

Because entries carry the principal, marketplace and SHA, the inventory question
— "every identity that fetched this exact content" — is a single query against
the ledger.

### Exporting it

The portal view is a recent-activity table; evidence lives in your compliance
system. The ledger is therefore also readable as a continuous feed, either
pulled as newline-delimited JSON or pushed to a registered *sink*.

A sink is nothing but a **cursor over the ledger** — the sequence of the last
entry it has been handed. No entry is copied into a per-consumer queue, which is
what keeps the append-only table the single copy of the evidence and makes replay
a write to one column. Delivery is at-least-once and each entry carries its
ledger sequence, so a receiver de-duplicates on that.

The ledger sequence is assigned before its row commits, so both paths withhold
the last few seconds of entries rather than risk a cursor stepping over an
append still in flight — bounded staleness in exchange for no gaps. See
[Exporting the audit ledger](../guides/exporting-the-audit-ledger.md).

### What the ledger is not

- Not hash-chained — there is no tamper-evidence beyond the append-only
  discipline of the code and your database controls.
- Not itself retained or archived by the product. How long you keep it is your
  compliance decision.
- Not a real-time alerting channel. Export is cursor-based and settles for a few
  seconds before an entry is handed on.

## Current scope limits

Both limits are enforced, not merely documented:

**Local sources by default.** Plugins must live inside the marketplace
repository as relative paths unless external plugin sources are enabled, which
they are not by default. An enabled gateway resolves a `github` source and
rewrites the manifest so every source is local again; `git`, `git-subdir`, `npm`
and `archive` are still rejected fail-closed. Whatever the setting, a snapshot is
held only when every source it declares resolves inside the snapshot the gateway
serves.

**Default branch only.** Registration accepts no ref other than `main`. Serving
additional refs is a future feature, implemented as promotion per
`(upstream, ref)`, with each ref advancing independently through the same gate.
