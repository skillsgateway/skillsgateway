# Exporting the audit ledger

Compliance teams do not read portals. This guide gets the append-only ledger —
every facade fetch and every administrative action — into a SIEM continuously and
without gaps.

Two shapes of the same feed:

| Path | Shape | Who holds the state |
| --- | --- | --- |
| **Pull** | `GET /api/audit/export` streams NDJSON. | The collector, as one number. |
| **Push** | A registered *sink* has batches POSTed to it, signed and retried. | The gateway, as the sink's cursor. |

!!! note "Telemetry is not this path"

    OpenTelemetry carries operational signal that may be sampled and dropped.
    Compliance evidence may not be. The ledger export is a separate, lossless
    path by design.

## The cursor

Both paths are a **cursor over the ledger**, not a copy of it. The cursor is the
`id` of the last ledger entry a consumer has been handed, and the ledger itself
is the queue — nothing is duplicated into a per-consumer outbox. That is what
makes replay a single write rather than a re-materialisation of history.

Every exported entry carries its ledger `id`, so it doubles as the receiver's
de-duplication key.

## Pull: NDJSON

```console
$ curl -D- 'localhost:8080/api/audit/export?after=0&limit=1000'
X-Skills-Gateway-Audit-Cursor: 842

{"id":1,"ts":"2026-08-15T09:04:11Z","source":"10.0.0.4","principal":"alice@example.com","marketplace":"acme","event":"info-refs","ref":"refs/heads/main","sha":"3f9c2ab..."}
{"id":2,"ts":"2026-08-15T09:04:11Z","source":"10.0.0.4","principal":"alice@example.com","marketplace":"acme","event":"upload-pack","ref":null,"sha":"3f9c2ab..."}
```

One compact JSON object per line, in ascending ledger sequence — the format
Splunk, Elastic and Sentinel ingest natively. A JSON array would force the
consumer to buffer the whole response; the stream is written and flushed in
fixed-size chunks, so gateway memory is bounded by the chunk rather than by
`limit`.

| Parameter | Default | Bounds |
| --- | --- | --- |
| `after` | `0` | The cursor to resume from. Negative values are treated as 0. |
| `limit` | `1000` | Clamped to `skills-gateway.audit-export.max-page-size` (`10000`). |

**The whole poller is a loop over one header.** Send `after`, read
`X-Skills-Gateway-Audit-Cursor` from the response, and use it as the next
`after`. When a response comes back empty the consumer is caught up.

```console
$ cursor=$(cat cursor.txt 2>/dev/null || echo 0)
$ next=$(curl -s -D headers.txt "localhost:8080/api/audit/export?after=${cursor}" \
    -o batch.ndjson && sed -n 's/^X-Skills-Gateway-Audit-Cursor: //Ip' headers.txt | tr -d '\r')
$ [ -s batch.ndjson ] && ingest batch.ndjson && echo "${next}" > cursor.txt
```

The endpoint sits behind the OIDC session like the rest of `/api/**`, so a
collector polls it with a session the same way the portal does; the portal's own
**Download ledger (NDJSON)** link is this endpoint with no cursor.

## Push: sinks

A sink is a named consumer with a stored cursor. Creating one also creates its
delivery channel — an ordinary webhook subscriber filtered to the single
`audit.export` event — so signing, retry, backoff and the delivery record are
[the lifecycle webhook machinery](lifecycle-webhooks.md), not a second delivery
engine.

=== "Portal"

    **Audit log** → **Export sinks** → **Sink name** and **Target URL** →
    **Add sink**. The signing secret appears in a show-once dialog.

=== "API"

    ```console
    $ curl -X POST localhost:8080/api/audit/sinks \
        -H 'Content-Type: application/json' \
        -d '{"name":"siem","url":"https://siem.example.com/ingest/skills-gateway",
             "after":0,"batchSize":500}'
    ```

    ```json
    {"id":1,"name":"siem","kind":"webhook",
     "url":"https://siem.example.com/ingest/skills-gateway",
     "cursorPosition":0,"batchSize":500,"secret":"whsec_...","createdAt":"..."}
    ```

| Field | Rule |
| --- | --- |
| `name` | `^[a-z0-9][a-z0-9_-]*$`. Shares a namespace with webhook subscribers, because the sink's channel *is* one — a collision is **409**. A bad name is **422**. |
| `url` | Same scheme allowlist as marketplace and subscriber registration, failing closed. **400**. |
| `after` | Ledger sequence to start after. Omit to start at the beginning. |
| `batchSize` | Entries per batch. Defaults to `skills-gateway.audit-export.batch-size`, clamped to `max-page-size`. |

A batch arrives as one signed POST whose body wraps the entries:

```json
{"event":"audit.export","sink":"siem","exportedAt":"2026-08-15T09:20:00Z",
 "fromCursor":800,"toCursor":842,"count":42,
 "entries":[{"id":801,"ts":"...","source":"10.0.0.4","principal":"alice@example.com",
             "marketplace":"acme","event":"info-refs","ref":"refs/heads/main","sha":"3f9c2ab..."}]}
```

Verify `X-Skills-Gateway-Signature` exactly as for a lifecycle event — HMAC-SHA256
over the raw body with the sink's `whsec_` secret. The delivery attempts show up
on the portal's [Webhooks page](../reference/portal.md#webhooks) alongside
lifecycle deliveries.

!!! note "A lifecycle subscriber never receives audit batches"

    `audit.export` is not a subscribable lifecycle event: `POST /api/webhooks`
    rejects it, and a `*` subscriber does not match it either. The only way to
    receive audit batches is to be a sink.

### At-least-once, and why

An export pass reads the entries after the cursor, enqueues one durable delivery
row, and only then advances the cursor. A crash between the two costs a
**duplicate**, never a gap. De-duplicate on each entry's ledger `id`; the
delivery-id header remains the transport-level de-duplication key.

!!! warning "A permanently failing sink stalls at its cursor"

    If the dispatcher exhausts the attempt budget the delivery is marked
    `failed` and the exporter does **not** rewind — a poisoned batch would
    otherwise loop forever. Fix the receiver, then replay explicitly.

## Replay

Replay is a cursor write, not a mode:

=== "Portal"

    The sink row's **Replay** button sets the position back to `0` and toasts
    *Sink '{name}' will replay the ledger*.

=== "API"

    ```console
    $ curl -X PUT localhost:8080/api/audit/sinks/1/cursor \
        -H 'Content-Type: application/json' -d '{"after":800}'
    ```

Everything after that position is delivered again on the next pass. **200** with
the updated sink, **404** if the sink is unknown.

The sink list also reports how far behind the ledger head each sink is, which is
the signal that a receiver is failing or that a batch size is too small for the
poll interval:

```console
$ curl localhost:8080/api/audit/sinks
```

```json
[{"id":1,"name":"siem","kind":"webhook","url":"https://siem.example.com/...",
  "cursorPosition":842,"ledgerHead":844,"behind":2,"batchSize":500,
  "enabled":true,"createdAt":"..."}]
```

`DELETE /api/audit/sinks/{id}` removes the sink and its delivery channel — **204**,
or **404** if it never existed.

## Why exports lag a few seconds

The ledger's `id` is a `BIGSERIAL`, and `nextval` is assigned **before** commit.
Entry 101 can therefore become visible while 100 is still in flight; a naive
`id > cursor` reader would advance past 100 and never see it again.

Both export paths close that window the same way: entries younger than
`skills-gateway.audit-export.lag` (default `5s`) are withheld. Ledger appends are
single-statement inserts that commit in milliseconds, so the settling window has
orders of magnitude of headroom, and the price is bounded staleness instead of a
silently skipped entry.

!!! warning "Do not tune the lag to zero"

    A shorter lag narrows the settling window; zero removes it, and the export
    can then step over an entry that was mid-commit. A compliance feed with a
    hole in it is worse than one that is five seconds behind.

The rest of the exporter tuning — poll interval, batch size, page-size caps — is
in [Configuration](../reference/configuration.md#audit-export).
