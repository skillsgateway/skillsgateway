# Design: external-vetting-connectors

## Context

The vetting chain is a trust boundary (`VettingChain`, `RevetVerdict`). An
external connector reaches a dependency the gateway does not control, so the
design is dominated by fail-closed behaviour. ADR 0009 records the contract-level
decision; this document is the feature-level detail.

## Decisions

### D1 — Configuration, not API/estate

External connectors bind from `skills-gateway.vetting.external[*]`, not an API.
The reason is the same as the license policy's (`SkillsGatewayProperties.License`):
a chain run must be **attributable**, and the identity and `version` of every
connector are stamped into the run's chain identity (GW_0049). An API-mutable
endpoint or position would make "content or chain?" unanswerable across runs.
Consequence: the declarative-estate obligation (#65) does not apply, because
nothing here is API-managed runtime state — recorded per the process rule.

### D2 — Registered as ordinary beans via an ImportBeanDefinitionRegistrar

`ExternalVettingConnectorRegistrar` binds the list from the `Environment` (so
`${...}` credential placeholders resolve) and registers one
`ExternalVettingConnector` bean per entry. They join the chain through the same
`List<VettingConnector>` injection the built-ins use, so `VettingService`,
ordering, recording and aggregation are untouched — which also keeps this change
off the toes of #221/#223 on the same package. A duplicate external name, or one
that shadows a built-in, is a startup failure.

### D3 — Synchronous HTTP; the gateway ships the content

The gateway POSTs `{snapshotId, marketplace, sha, files[]}`. Quarantined content
is never served through the facade, so the endpoint cannot pull it; the gateway
sends the scannable bundle a built-in would walk (binary/oversized files are
present but marked `scanned=false`, never dropped). The client uses the same
`JdkClientHttpRequestFactory` + `RestClient` pattern as `WebhookDispatcher`, with
per-connector connect/read timeouts.

### D4 — Fail-closed mapping (the failure model)

Failure modes and the layer that catches each:

| Mode | Handling |
| --- | --- |
| Connection refused / DNS / connect timeout / reset | caught → `ERROR` |
| Read timeout | JDK read timeout → `ERROR` |
| Non-2xx | status check → `ERROR` |
| Empty body | length check → `ERROR` |
| Oversized body | bounded read returns null → `ERROR` |
| Unparseable body / JSON null | Jackson guard → `ERROR` |
| Unrecognised / missing `state` (incl. `error`) | state parse → `ERROR` |
| Malformed finding (missing id/message, unknown severity) | finding validation → `ERROR` |
| Snapshot bundle over request cap | bundle returns null → `ERROR` |

`ERROR` aggregates as blocking (`VettingChain`), and `ExternalVettingConnector`
returns the error rather than throwing (defence in depth: `VettingService` would
catch a throw too, but a returned `Verdict.error` keeps the reason in the finding
a reviewer reads).

### D5 — Worst-of

`effective = worst(declaredState, Verdict.of(findings).state())` over the
clearing/failing states. `pending` is handled before worst-of and stays
`PENDING`. This prevents an endpoint from declaring a state weaker than its own
evidence.

### D6 — Response caps bound a hostile endpoint

`max-response-bytes` bounds the read (memory), `max-request-bytes` bounds the
bundle; both fail closed when exceeded. The endpoint URL is operator-configured
(trusted), so there is no SSRF surface from user input.

## Failure model (old-coder Tier 3)

The specific ways this change can hurt, each with a covering test layer:

- **Silent pass on a broken dependency** → the adversarial matrix
  (`ExternalVettingConnectorUnitTests`, GW_0145): unreachable, refused, timeout,
  non-2xx, empty, oversized, unparseable, unknown state, malformed finding, over-cap
  bundle — each asserted `ERROR`/blocked.
- **Memory exhaustion from a hostile response** → oversized-response and
  bounded-read tests.
- **A misconfigured endpoint passing condemned content** → worst-of test.
- **Silence read as approval** → `pending` blocks test.
- **A bad config silently dropping/shadowing a connector** → registrar uniqueness
  and reserved-name guards.

Not covered (declared limits): the inbound asynchronous resolution callback is
out of scope; TLS/mTLS to the endpoint beyond a bearer credential is a decision to
confirm.

## Setup plan

No new dependencies. JDK `com.sun.net.httpserver.HttpServer` (test only) provides
a real in-process endpoint for the adversarial tests; Jackson and Spring
`RestClient` are already present. Git checkpoints at spec and each green step per
the repo's old-coder authorization.

## Open questions (for the owner)

1. **Credential model** — bearer-in-header assumed; mTLS/request-signing wanted?
2. **Config vs estate** — confirmed config for attribution; any near-term need for
   per-marketplace, API-managed external connectors?
3. **Sync-vs-webhook default** — synchronous default with a `pending` seam;
   confirm the inbound resolution callback is a separate follow-up (portal #224).
4. **Chain-position semantics** — external `order` shares the integer space with
   the built-ins (`secret-scan=100`); confirm positioning by absolute order.
