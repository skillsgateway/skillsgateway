# Design: external-vetter-timeout

## Context

See proposal.md — Why. Today `VettingService.runGuarded` waits
`skills-gateway.vetting.timeout` for every vetter. `ExternalVettingConnector`
builds its HTTP client once, at construction, from `connect-timeout` and
`read-timeout`; `ExternalConnectorProperties` defaults an unset `read-timeout`
to a fixed 30 seconds. Connectors are registered by
`ExternalVettingConnectorRegistrar`, which binds `skills-gateway.vetting.external[*]`
straight off the `Environment` before any `@ConfigurationProperties` bean
exists.

## Goals / Non-Goals

- Goal: one effective limit per vetter, so the limit an operator configures is
  the one that fires.
- Non-goal: a per-built-in timeout, or any new configuration leaf.
- Non-goal: changing what a timeout records — it stays an `ERROR` verdict.

## Decisions

### D1 — The vetter reports its own time limit

`Vetter` gains `default Duration timeLimit(Duration chainTimeout)` returning
`chainTimeout`; `runGuarded` waits `vetter.timeLimit(properties.timeout())` and
names that value in the "timed out after" message. `ExternalVettingConnector`
overrides it with `connect-timeout + read-timeout + 5s`.

Alternative: an `instanceof ExternalVettingConnector` branch in
`VettingService`. Rejected: the service would have to know connector internals,
and the SPI already says a vetter may be slow — letting it state how slow keeps
the knowledge where the timeouts live.

### D2 — The margin is a fixed 5 seconds

The connector's limit must exceed the HTTP client's own timeouts, or the chain
fires first and the error says "timed out after PT35S" instead of naming the
HTTP timeout. The margin covers the work around the call (building the bundle,
which reads at most `max-request-bytes`, and mapping a response capped by
`max-response-bytes`). It is a constant, not a setting, because an operator
gains nothing from tuning it and it would be a new configuration leaf.

### D3 — An unset `read-timeout` inherits the chain-wide limit at registration

`ExternalConnectorProperties` keeps an unset or non-positive `read-timeout` as
`null` (meaning "inherit"). The registrar binds `skills-gateway.vetting` to
`SkillsGatewayProperties.Vetting` from the same `Environment` (so the 30-second
default stays declared in one place) and passes its `timeout` to the
connector's constructor, which resolves the effective read timeout once.
Alternative: resolving it in `VettingService` per run — rejected because the
JDK HTTP client's read timeout is fixed when the client is built.

### D4 — Requirement wording

`GW_VETTING_0002 — Fail-closed vetting chain aggregation` already says "exceeds
its time limit", which stays true and says nothing about which limit, so it is
unchanged. `GW_VETTING_0025 — An external vetting connector fails closed on any
inconclusive answer` names the connector's connect and read timeouts; it is
revised (0.3.0) to say those, not the chain-wide limit, bound the wait, and that
an unset read timeout takes the chain-wide limit. New SVC `SVC_GW_VETTING_0062`
verifies both halves plus the built-in staying on the chain-wide limit. The id
was checked against in-flight `openspec/changes/` and open PR branches; none
reserves it.

## Risks / Trade-offs

- [The out-of-the-box deadline for an external vetter grows from 30s to 40s
  (5s connect + 30s read + 5s margin)] → it is the HTTP client's 30s read timeout
  that now fires, which is the limit the operator configured; the hang guard
  for a wedged vetter is still bounded.
- [A connector with a very large `read-timeout` holds a chain run that long] →
  that is what the operator configured; the built-in vetters are unaffected.

## Migration Plan

None: no schema or API change. A deployment that raised
`skills-gateway.vetting.timeout` only to make room for a slow connector can
lower it again.
