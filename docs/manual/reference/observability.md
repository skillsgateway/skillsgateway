# Observability

The gateway records its own metrics and observation-based spans
unconditionally, through the auto-configured Micrometer registries; whether
anything is **exported** is a deployment decision. There is no
`skills-gateway.*` property for this — there is nothing to configure on the
gateway's side.

## Metrics

All gateway metrics live under the `skills_gateway` prefix. Every tag is a
closed vocabulary: per-marketplace, per-SHA and per-identity dimensions are
deliberately **not** metric tags — they live in the
[adoption endpoints](api/adoption.md) and the
[audit ledger](api/audit.md), where cardinality is a query result rather than
a time series.

| Metric | Type | Tags | Recorded around |
| --- | --- | --- | --- |
| `skills_gateway.ingestion` | timer (observation) | `outcome=success\|error` | Each ingestion — upstream fetch, pin, snapshot record, vetting trigger. |
| `skills_gateway.approval` | timer (observation) | `decision=approve\|reject`, `outcome=success\|error` | Each approval decision. A vetting-blocked approval lands on `outcome=error` and still surfaces its refusal unchanged. |
| `skills_gateway.facade.fetches` | counter | `event=info-refs\|upload-pack` | Every facade fetch entry as it is appended to the ledger. |

The ingestion and approval instruments are Micrometer *observations*, so when
tracing is enabled they also produce spans with the same names and tags.
Facade requests already carry the standard HTTP server observation for
`/git/**`; the counter adds the event-kind dimension without a second span.

Standard JVM, HTTP server and datasource metrics come from Spring Boot actuator
as usual.

### Storage backend

These are recorded on both storage backends and move only on `object-store`
(see [Configuration](configuration.md)). They are how the compare-and-swap
design is checked against its own assumptions rather than trusted.

| Metric | Type | Tags | Recorded around |
| --- | --- | --- | --- |
| `skills_gateway.storage.conditional_write.conflicts` | counter | — | Conditional writes the store refused because another writer had moved the repository manifest on. |
| `skills_gateway.storage.conditional_write.retries` | counter | — | Transition attempts made after such a refusal. |
| `skills_gateway.storage.conditional_write.exhaustions` | counter | — | Transitions abandoned because the bounded retry ran out. **Alert on this**: it is a reference transition that did not happen. |
| `skills_gateway.storage.manifest.refreshes` | counter | — | Freshness checks that found the reference map had moved under a cached copy. |
| `skills_gateway.storage.wal.depth` | gauge | — | Write-ahead entries not yet folded into a manifest, summed across the repositories this replica has written to. Exact as of each maintenance pass. |
| `skills_gateway.storage.packs.live` | gauge | — | Packs the manifests this replica has read say are live, summed the same way. |
| `skills_gateway.storage.pack_cache.hits` | counter | — | Pack opens served from the local on-disk cache. |
| `skills_gateway.storage.pack_cache.downloads` | counter | — | Packs fetched from the store into that cache — the other half of the hit rate. |
| `skills_gateway.storage.requests` | timer | `operation=get\|conditional-get\|open\|stat\|put\|conditional-put\|create\|list\|delete\|probe`, `outcome=success\|error` | Every object-store request. A conditional write the store *refused* is `outcome=success` — the store answered correctly; the conflict counter above is where a refusal means something. |

Two of these are the ones to watch. `conditional_write.exhaustions` is a failed
reference transition and belongs on an alert. `wal.depth` and `packs.live`
climbing without falling back is compaction falling behind, which shows up as a
slow restore long before it shows up as anything else.

### The read-only forge mirror

Recorded on every gateway. A deployment with no mirror configured publishes them
as zeros, which is what "nothing has diverged" looks like and is true. Turning
the mirror on is in
[The read-only forge mirror](../guides/read-only-forge-mirror.md).

| Metric | Type | Tags | Recorded around |
| --- | --- | --- | --- |
| `skills_gateway.mirror.stale_refs` | gauge | — | References the mirror still holds that the facade no longer serves. **Alert on this**: in the ordinary case they are a snapshot the gateway revoked, so a value above zero outstaying one `sweep-interval` means the mirror is showing content the gateway has withdrawn. |
| `skills_gateway.mirror.missing_refs` | gauge | — | Served references the mirror lacks or holds at another commit. A visibility gap rather than a security one. |
| `skills_gateway.mirror.reachable` | gauge | — | `1` when the last look at the mirror reached it, `0` when it did not. |
| `skills_gateway.mirror.seconds_since_success` | gauge | — | Seconds since a reconciliation last succeeded; `-1` when none has since startup. Climbing without bound is a code host the gateway cannot write to. |
| `skills_gateway.mirror.reconciliations.ok` | counter | — | Reconciliations that brought the mirror into line, or found it already there. |
| `skills_gateway.mirror.reconciliations.failed` | counter | — | Reconciliations that exhausted their retries, or that refused to act on a served reference set the gateway's own records contradicted. |

Read `stale_refs` and `seconds_since_success` together, always. The counts are
written by reconciliations and by the drift report, never computed when a
monitoring system scrapes — a gauge that contacted a code host on read would put
a third party on the scrape path. So a gateway that cannot reach the mirror does
not know what it holds, and `reachable` at `0` with `seconds_since_success`
climbing is what says the count beside them is old rather than low.

## Health

`/actuator/health` carries a `gitStorage` indicator on both backends. It names
the backend actually in use — which is the point, since a gateway serving from
local disk while the operator believes it is serving from a bucket looks healthy
from every other angle.

| Backend | Up when | Details |
| --- | --- | --- |
| `filesystem` | the data directory exists and is writable | `backend`, `dataDir` |
| `object-store` | the bucket answers a listing of the gateway's own prefix | `backend`, `bucket`, `region`, `conditionalWrites` |

The indicator **reads and never writes**. The conditional-write check that
decides whether a store can serialize reference transitions at all is a startup
gate — a gateway for which it failed never started — so its result is *reported*
here (`conditionalWrites: verified at startup`) rather than repeated on every
poll.

## Exporting

Export is opt-in and off by default: `arconia.otel.enabled=false` in the
shipped configuration, so builds, tests and default deployments never attempt
OTLP export. To publish telemetry, a deployment sets `arconia.otel.enabled=true`
(property or `ARCONIA_OTEL_ENABLED=true`) and points the standard OpenTelemetry
`OTEL_EXPORTER_OTLP_*` environment variables at its collector. The
`skills_gateway.*` instruments flow immediately — they were being recorded all
along.

## Local stack

For local work the `observability` profile starts a Grafana LGTM dev container
(collector, Loki, Tempo, Prometheus, Grafana) and enables export to it:

```console
$ ./mvnw spring-boot:run -Dspring-boot.run.profiles=observability
```

The Grafana URL is logged at startup. The supporting dependencies are optional,
so nothing of the dev stack is reachable from the packaged jar or the container
image built from it.
