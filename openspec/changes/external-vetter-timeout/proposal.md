# Proposal: an external vetter's own timeouts are its time limit in the chain

## Why

An external vetting connector has two time limits and the smaller one silently
wins: its own `skills-gateway.vetting.external[n].read-timeout` and the
chain-wide `skills-gateway.vetting.timeout`, which `VettingService` applies to
every vetter. Both default to 30 seconds, so a connector configured with
`read-timeout: 5m` — an LLM reviewer, a sandbox — is still cut off at 30
seconds. The only workaround is raising the chain-wide value, which loosens the
hang guard on the built-in vetters as well. Found while writing the LLM vetter
example (#629), which has to document that workaround.

## What Changes

- An external vetter's deadline in the chain is its own `connect-timeout` plus
  `read-timeout` plus a small fixed margin, so the HTTP client gives up first
  and the recorded error names the timeout that actually fired.
- A connector that sets no `read-timeout` takes `skills-gateway.vetting.timeout`
  as its read timeout, instead of a fixed 30 seconds. Out of the box nothing
  changes, because both defaults are 30 seconds.
- Built-in vetters keep the chain-wide `skills-gateway.vetting.timeout`.
- Still fail-closed: an external vetter that exceeds its limit is an `ERROR`
  verdict, which blocks (`GW_VETTING_0002 — Fail-closed vetting chain
  aggregation`, `GW_VETTING_0025 — An external vetting connector fails closed
  on any inconclusive answer`).
- No new configuration leaf and no new Spring test context:
  `ConfigSurfaceBudgetTests` and `ContextBudgetTests` do not move. This narrows
  an existing surface (two overlapping limits become one per vetter) rather than
  adding one.
- Not **BREAKING**: no API or schema change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: `GW_VETTING_0025 — An external vetting connector fails
  closed on any inconclusive answer` is revised to say that the connector's
  connect and read timeouts, not the chain-wide limit, bound how long the chain
  waits for it, and that an unset read timeout takes the chain-wide limit; new
  `SVC_GW_VETTING_0062` verifies it.

## Impact

- `Vetter` (a default per-vetter time limit), `VettingService.runGuarded`,
  `ExternalVettingConnector`, `ExternalConnectorProperties`,
  `ExternalVettingConnectorRegistrar`.
- Docs: `reference/configuration.md` (`vetting.timeout` and
  `external[n].read-timeout` rows), `concepts/vetting.md`,
  `guides/adding-an-external-vetter.md`.
- #629 can drop its `timeout: 5m` workaround once both have merged.
