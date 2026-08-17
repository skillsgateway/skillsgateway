# Syncing from upstream automatically

Ingestion is manual by default: content enters quarantine when an operator
clicks **Ingest** or calls the API. A marketplace can instead be polled on a
schedule, or ingest the moment its forge announces a push. The **sync mode** is
per marketplace, and it only changes the *trigger* — every snapshot still lands
**held** behind the same approval gate, whatever pulled it in.

| Mode | Trigger | Latency |
| --- | --- | --- |
| `on-demand` (default) | An operator, via portal or API | none — but someone has to remember |
| `scheduled` | The gateway's polling sweep | up to one poll interval |
| `webhook` | A signed forge push webhook | seconds |

## Changing the mode

```console
$ curl -X PUT localhost:8080/api/marketplaces/acme/sync \
    -H 'Content-Type: application/json' -d '{"mode":"scheduled"}'
```

The change is audit-logged (`sync-mode-changed`) with your identity and the new
mode.

## Scheduled polling

Marketplaces in `scheduled` mode are ingested by a background sweep — least
recently attempted first, in bounded batches, so a large estate is covered in
rotation rather than all at once. A failed fetch is logged, stamped, and
retried on a later pass without stopping the rest of the batch.

The interval and batch size are global settings; see
[Configuration](../reference/configuration.md#upstream-sync).

## Webhook-triggered ingestion

Switching a marketplace to `webhook` mode generates an HMAC secret:

```console
$ curl -X PUT localhost:8080/api/marketplaces/acme/sync \
    -H 'Content-Type: application/json' -d '{"mode":"webhook"}'
```

```json
{"marketplace":{"name":"acme","syncMode":"webhook",...},
 "webhookSecret":"9f2c...64 hex chars...ab41"}
```

!!! danger "Shown exactly once"

    The secret is returned only by this response — no read endpoint ever
    exposes it. Lost it? Set `webhook` mode again: that generates a fresh
    secret and invalidates the old one, which is also how you rotate it.

Then configure the forge webhook:

- **Payload URL**: `https://skills.corp.example/hooks/acme`
- **Content type**: anything — the payload is ignored (see below)
- **Secret**: the value from the response
- **Events**: pushes to the default branch are the useful signal; other events
  cause harmless redundant fetches

GitHub, Gitea, and Forgejo sign deliveries with the compatible
`X-Hub-Signature-256` header out of the box. Any other sender works too: sign
the raw request body with HMAC-SHA256 and send `sha256=<hex>` in that header.

### The payload is ignored, on purpose

A valid signature means exactly one thing: *poll this marketplace now*. The
gateway ingests the **registered** upstream URL's default branch — nothing in
the request body (URLs, refs, commit SHAs) is ever read. The worst a
forged-but-signed request can cause is a fetch the schedule would have done
anyway, landing held in quarantine. The endpoint answers `202 Accepted`
immediately and ingests in the background; the snapshot appears in the portal
moments later.

Requests with a missing or wrong signature get `403` and ingest nothing.
Unknown marketplaces — and marketplaces not in `webhook` mode — get `404`.
Bodies over the configured bound get `413` before the signature is even
checked.

## Upstream outages don't reach consumers

The facade serves the last approved snapshot regardless of upstream health. A
scheduled or webhook-triggered fetch that fails because upstream is down is
logged and retried later; no snapshot state changes, no published ref moves,
and a `git fetch` through the facade during the outage returns exactly what it
returned before. Consumers depend on the gateway's vetted copies, not on
upstream uptime — automating ingestion does not couple them back together.

## What lands on the ledger

Every sync-triggered ingestion is recorded like a manual one, with the trigger
as the acting identity — `scheduler` or `webhook` instead of a person — and
emits the ordinary `snapshot.ingested` lifecycle event. Mode changes are
recorded with the operator who made them. An auditor reads which content
arrived, when, and on whose (or what) authority from the ledger alone.
