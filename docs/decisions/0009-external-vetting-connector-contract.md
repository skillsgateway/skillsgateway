# ADR 0009 — The external vetting connector contract: configured, synchronous, fail-closed

*Proposed, 2026-09-01.*

## Context

Issue [#222](https://github.com/skillsgateway/skillsgateway/issues/222) asked for
what issue [#10](https://github.com/skillsgateway/skillsgateway/issues/10)
earmarked but never built: a way for an operator to add their **own** vetting
connector — an LLM endpoint IT Security has prepared, a sandbox detonator, a
corporate scanner — instead of being limited to the three compile-time built-ins
(`secret-scan`, `prompt-injection`, `license-scan`).

The vetting chain is a trust boundary. Its fail-closed aggregation
(`VettingChain`) is what stands between an unvetted snapshot and the approval
gate, and `RevetVerdict` already writes down why an *error* — a scanner that
threw or timed out — must never be read as a clean pass. An external connector
reaches a dependency the gateway does not control, so it multiplies exactly the
failure modes that principle exists for: the endpoint can be down, slow, hostile,
or simply wrong.

Three things about the existing design made this addable without disturbing the
gate:

- `VerdictState.PENDING` already exists and already aggregates as blocking — the
  seam for an asynchronous connector was cut before one existed.
- `Verdict` already carries `report-url` for an external report.
- Connectors are ordinary `List<VettingConnector>` beans, so anything registered
  as one joins the chain through the same ordering, recording and aggregation.

## Decision

**An external connector is a configured, synchronous HTTP participant in the
existing chain, and every way it can fail to produce a trustworthy verdict is an
error that blocks.**

### 1. Configuration, not API-managed state

External connectors are declared under `skills-gateway.vetting.external[*]`
(name, URL, order, version, description, credential, timeouts, size caps) and
bound into one `ExternalVettingConnector` bean each. This mirrors the
`skills-gateway.vetting.license` decision: **vetting policy must be attributable
per chain run** (GW_0049), so the identity and version of every connector that
ran is stamped into the run. A connector whose endpoint, position or version
could change through the API between two runs would make "cleared last month,
blocks today — content or chain?" unanswerable. Configuration binds the chain to
the deployment. Because nothing here is API-mutable runtime state, the
declarative-estate obligation (#65) does not apply; this is called out in the
change's design, as the process rules require.

### 2. Synchronous request/response is the default; the credential is write-only

The gateway POSTs the snapshot bundle — identity plus the scannable file content
a built-in connector would walk, since quarantined content is never fetchable
through the facade — and reads back the normalized
`{state, report-url, findings[]}`. The configured credential is sent in a
configurable header (default `Authorization: Bearer`), referenced from an
environment variable (`${...}`) and never logged, audited or echoed, exactly like
the declared-webhook secret.

### 3. Fail-closed at every branch (the load-bearing half)

`ExternalVettingConnector` records an **error** verdict — which blocks — when the
endpoint is unreachable, refuses the connection, or exceeds the connect/read
timeout; when it returns a non-2xx status, an empty body, a body over the
response cap, or an unparseable body; when it declares a state the gateway does
not recognise or a finding it cannot validate; and when the snapshot's scannable
content exceeds the request cap, because a partial bundle would earn a verdict
about content the connector never saw. There is no branch that yields a clearing
verdict without a well-formed, in-bounds, understood answer.

### 4. Worst-of, so an endpoint cannot pass what its own findings condemn

The recorded state is the more severe of the state the endpoint **declared** and
the state its returned **findings** imply. An endpoint that answers `pass`
alongside a `critical` finding is recorded as `fail`. This closes the gap a
misconfigured or compromised reviewer would otherwise open, without discarding
its judgement where it is stricter.

### 5. Asynchronous answers block; the resolution callback is deferred

A `pending` answer is recorded as `PENDING`, which blocks until resolved. This
admits the async contract safely **before** any inbound mechanism exists to
resolve it: an unresolved `pending` simply keeps the snapshot held, which is the
correct default. The inbound resolution callback endpoint — a new,
authenticated, trust-boundary surface of its own — is deliberately a **separate,
sequenced** piece of work, not smuggled in half-built here.

## Alternatives considered

- **API/estate-registered connectors.** Rejected as the default for the
  attribution reason in Decision 1; it is the natural home for a *future*
  per-marketplace connector, and is listed among the decisions to confirm.
- **Trust the declared state verbatim.** Rejected: worst-of costs nothing and
  removes a silent-pass path.
- **Build the inbound webhook resolution now.** Rejected for this change: it is a
  new unauthenticated-until-verified inbound boundary that deserves its own
  design and adversarial tests; shipping it half-built would be worse than the
  fail-closed `PENDING` that already holds.
- **Send a fetch handle instead of content.** Rejected for v1: quarantined
  content is not served, so the external service cannot pull it; the gateway
  ships a bounded bundle. Revisit if bundles get large.

## Consequences

- Operators can extend the chain without code changes, and every external verdict
  is recorded, audited and surfaced like a built-in one.
- A flaky or hostile external endpoint blocks approvals rather than passing
  content — an availability cost paid deliberately for a safety guarantee, the
  same trade the built-in timeout already makes.
- The async contract is defined and safe, but end-to-end asynchronous review is
  not complete until the resolution callback ships.

## Decisions to confirm with the owner

1. **Credential model.** Bearer-in-a-header (default) is assumed; confirm whether
   mTLS or request signing is wanted for the first real endpoint.
2. **Config vs estate placement.** Configuration is chosen for per-run
   attribution; confirm there is no near-term need for per-marketplace,
   API-managed external connectors.
3. **Sync-vs-webhook default.** Synchronous is the default and `pending` is the
   async seam; confirm the inbound resolution callback is a separate follow-up
   (portal work is #224).
4. **Chain-position semantics.** External `order` shares the integer space with
   the built-ins (`secret-scan=100`, …); confirm operators positioning around the
   built-ins by absolute order is the intended model.

Supersedes nothing. Extends the vetting-connector model of
[ADR 0002](0002-toolchain-and-product-decisions.md) and the vetting roadmap in
`architecture.md` §14.2.
