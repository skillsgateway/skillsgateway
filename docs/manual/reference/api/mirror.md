# Mirror

The read-only forge mirror's two endpoints: a comparison between the references
the mirror holds and the references the facade serves, and a request to reconcile
the two now. Turning the mirror on is
in [Configuration](../configuration.md#read-only-forge-mirror); what it is for
and what it is not is in
[The read-only forge mirror](../../guides/read-only-forge-mirror.md).

Under role enforcement both are **admin-only** — not auditor reads. They name an
outbound integration target and the state of a push credential's last use, which
is deployment infrastructure rather than a record of what the gateway served to
whom; the reconcile route is the one that actually exercises that credential.

**Machine reach.** None. No API scope reaches either endpoint, for the same
reason. See [Machine API credentials](tokens.md#machine-api-credentials).

---

## `GET /api/mirror/drift`

Reads the mirror's references now and diffs them against published storage.

```console
$ curl localhost:8080/api/mirror/drift
```

```json
{"enabled":true,"marketplace":"corp-marketplace",
 "url":"https://forge.example.com/mirrors/corp-marketplace.git",
 "reachable":true,"inSync":false,
 "servedTip":"9f2c1d4e6a7b8c9d0e1f2a3b4c5d6e7f80912345",
 "mirrorTip":"1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d",
 "missingOnMirror":["refs/heads/main","refs/snapshots/9f2c1d4e6a7b8c9d0e1f2a3b4c5d6e7f80912345"],
 "staleOnMirror":["refs/snapshots/1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d"],
 "pendingUpdates":0,
 "lastAttemptAt":"2026-09-06T21:14:03Z","lastAttemptOutcome":"failed",
 "error":"IOException: mirror refused refs/heads/main: REJECTED_OTHER_REASON"}
```

| Field | Meaning |
| --- | --- |
| `enabled` | Whether a mirror is configured. Everything below is null, empty or `false` when it is not. |
| `marketplace` | The single mirrored marketplace. |
| `url` | The mirror's clone URL. Never carries a credential — one embedded in the URL is refused at startup. |
| `reachable` | Whether the gateway could read the mirror's references for this report. |
| `inSync` | Whether the mirror holds exactly the served references. **False whenever `reachable` is false**: silence is never reported as agreement. |
| `servedTip` | The commit `refs/heads/main` resolves to in published storage, or null when the marketplace serves nothing. |
| `mirrorTip` | The commit `refs/heads/main` resolves to on the mirror, or null. |
| `missingOnMirror` | Served references the mirror lacks or holds at a different commit. |
| `staleOnMirror` | References the mirror still holds that are no longer served — what a revocation that has not reached the mirror leaves behind. |
| `pendingUpdates` | Mirror updates queued or in flight right now. A non-zero value means the picture is mid-change. |
| `lastAttemptAt` | When the mirror was last updated, or attempted. |
| `lastAttemptOutcome` | `ok`, `failed`, or `none` if nothing has been attempted since this gateway started. |
| `error` | Why the comparison or the last attempt failed, or null. An error beginning `refused:` means the gateway read its own published storage and would not act on what it read — the mirror was left untouched and the problem is upstream of the code host. |

The comparison is made against published storage, not against what the gateway
last tried to push, so a mirror somebody changed by hand shows up here as drift
rather than as a successful last push.

Only the served namespaces are compared — `refs/heads/main` and
`refs/snapshots/*`. A branch, tag or `HEAD` belonging to the mirror repository
itself is neither reported as drift nor removed by the gateway.

`200` for an administrator, whether or not a mirror is configured; `403`
otherwise.

!!! note "The report says nothing about what clients receive"

    Drift is a statement about the mirror, never about the facade. A mirror that
    is stale, unreachable or wrong does not change which snapshot the facade
    serves, and a revoked snapshot is off the facade whatever this report says.

---

## `POST /api/mirror/reconcile`

Reconciles the mirror with what the facade serves now, and answers with the
resulting comparison — the same body as `GET /api/mirror/drift`.

```console
$ curl -X POST localhost:8080/api/mirror/reconcile
```

Use it after fixing a code-host outage, rotating a rejected credential, or
enabling the mirror on a marketplace whose last approval is in the past, rather
than waiting for the recurring reconciliation.

`POST` rather than `GET` because it changes a remote system. It waits for the
reconciliation rather than acknowledging it, so the body describes the mirror
after the attempt; a request whose wait runs out answers with `pendingUpdates`
above zero rather than failing.

`200` for an administrator, whether or not a mirror is configured — a gateway
with none answers `{"enabled": false, ...}` and contacts nothing. `403`
otherwise.

!!! note "It cannot affect what the facade serves"

    Like every other mirror path, this one reads published storage and writes to
    the code host. Nothing about approval, revocation or the facade is on it, and
    a code host that never answers costs the request its wait and nothing else.

!!! warning "A reconciliation may refuse"

    If published storage answers successfully but incompletely, the gateway
    declines to push or delete anything rather than acting on a reference set it
    cannot believe: the response carries `lastAttemptOutcome` `failed` with an
    `error` beginning `refused:`, and the ledger records
    `mirror-reconciliation-refused`.
    That points at the gateway's own storage, not at the code host — see
    [The read-only forge mirror](../../guides/read-only-forge-mirror.md#mirror-reconciliation-refused).
