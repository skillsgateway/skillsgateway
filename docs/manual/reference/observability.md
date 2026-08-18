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
and test scope again under the `native` profile: nothing of the dev stack is
reachable from the packaged jar, the container image or the native binary.
