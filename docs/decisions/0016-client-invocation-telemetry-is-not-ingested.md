# ADR 0016 — Client invocation telemetry is not ingested; the gateway publishes presence instead

*Proposed, 2026-09-08.*

## Context

Issue [#85](https://github.com/skillsgateway/skillsgateway/issues/85) carries a
framing from an internal security review that is worth keeping: **distribution ≠
invocation ≠ adherence.** The gateway measures the first gate precisely. It does
not measure the second, and the third is not its business at all. The issue asks
for the second: ingest client OpenTelemetry — Claude Code exports usage metrics —
to enrich the fetch-derived install inventory with invocation-level data, and
extend the adoption dashboards with an invocation measure per served skill.

The first gate is measured precisely for a reason that is easy to lose sight of.
Every figure behind
GW_OBSERVABILITY_0001 — *Adoption reporting from the fetch ledger*,
GW_OBSERVABILITY_0002 — *Staleness reporting against the served tip* and
GW_OBSERVABILITY_0004 — *Adoption page in the admin portal*
is derived from bytes this gateway served, to an identity this gateway
authenticated, appended to the ledger before the response left. They are not
reports *about* fetches. They are reports *of the gateway's own actions*, which is
why no client can move them and why
[ADR 0008](0008-serving-surface-stays-the-embedded-facade.md) declined to serve
from an external forge rather than give that property up.

An invocation number does not have that property, and cannot be given it. So the
question this ADR answers is not "can we build an ingestion endpoint" — that is a
week — but whether a number of this kind belongs in this system's database, API
and dashboards at all.

## What the client actually exports

Two findings from the vendor documentation decided this, and neither was the
finding the issue anticipated.

**There is no invocation metric.** Claude Code's metric instruments count
sessions, lines of code, commits, pull requests, cost, tokens, edit-tool
decisions and active time. None counts a skill firing. `skill.name` rides as an
*attribute* on `claude_code.cost.usage` and `claude_code.token.usage`, and on the
`claude_code.api_request` / `api_error` / `api_refusal` **events**. An invocation
count is therefore always a derived quantity — datapoints carrying a skill
attribute, or `tool_result` / `tool_decision` events with the Skill tool — and the
crisper of those signals lives in the log stream, not the metric stream, at
per-event volume.

**The identifier is redacted for exactly the population this gateway serves.**
The documented rule: skill names appear verbatim for built-in, bundled,
user-defined and *official-marketplace* plugin skills; **third-party plugin skill
names are replaced with the literal `"third-party"`**. `plugin.name` follows the
same rule. `marketplace.name` is *only emitted for official-marketplace plugins*
and is absent otherwise.

A marketplace this gateway serves is a third-party marketplace. That is not a
configuration mistake; it is what the product is. So for plugin-delivered
content, the join key the entire feature rests on arrives as a constant, and the
marketplace attribute that would have identified the gateway does not arrive.

There is a partial exception, and it is worse than no exception. Content consumed
as plain `SKILL.md` directories — the second adapter in
[architecture.md §11](../manual/architecture.md) — installs as *user-defined*
skills and its names appear verbatim. A deployment running both adapters would
get real names for one half of its estate and `"third-party"` for the other, and
the half that vanishes is the plugin-delivered half the Claude Code adapter is the
most complete part of. A dashboard rendering that looks complete while
under-reporting systematically. Silence would be safer than a number with a hole
shaped like the thing you care about most.

## The trust question

**What an invocation datapoint is evidence of:** a process on a developer machine
chose to report that a skill was active on a model request. Not that the skill
fired. Not that its guidance reached the model's output. Not that a human read it.

**Can a client forge it, including another team's numbers?** Yes, and
structurally rather than through a bug:

- The credential is a bearer header (`OTEL_EXPORTER_OTLP_HEADERS`), distributed by
  managed settings to **every machine being measured**. There is no per-identity
  key and no signature over a datapoint.
- Every identifying attribute is client-asserted. `user.email` is whatever the
  posting process says it is. `user.id` is documented as a random value persisted
  in a file the user can delete for a fresh one.
- Managed settings lock the *destination*, which stops a developer redirecting the
  stream elsewhere. They do nothing about what any process on that machine posts
  *to* that destination.
- Under-reporting is cheaper still and needs no credential at all: do not run the
  exporter, or work where managed settings do not apply.

This is unremarkable for a productivity dashboard. It is not unremarkable here,
for two reasons.

**Borrowed credibility.** The gateway's other adoption figures are unforgeable by
construction. Placing a client-asserted number in the same report, on the same
page, under the same word — *adoption* — transfers that credibility to it. The
integrity story is not weakened by a weaker number existing somewhere; it is
weakened by the two being presented as one kind of thing. That is not a defect a
footnote fixes once the number lives in the gateway's own tables and API.

**Adoption numbers here are decision inputs, not vanity.** They drive recall and
they are the argument for keeping the paved road. "Nobody invokes this" is an
argument to retire a control. "Everybody does" is an argument that the gate is
working. Both are conclusions an adversary — or a team under measurement pressure
— would like to influence, and here both are cheap to influence.

## Privacy and proportionality

The issue's "who actually uses it" is two questions wearing one sentence, and
separating them is what makes the collection question easy.

- **Recall** — *which identities hold skill X, so they can be told* — genuinely
  needs per-identity attribution. It is already answerable from the ledger, which
  the gateway owns and no client can move. It does not need telemetry.
- **Usage** — *is skill X worth keeping* — needs a count per skill per window.

Once recall is off the table, **nothing left needs per-user invocation data**. So
per-user attribution is designed out rather than collected and then restricted.

That matters because of what ingestion would have to hold. `user.email` is
documented as *always included when available*; session ids, per-request timings
and tool outcomes ride alongside. This is behavioural data about identifiable
people, and it would land next to an append-only ledger built for long retention
and streamed to audit sinks. The property that makes the ledger good evidence — it
cannot be edited — is exactly the property an erasure request cannot be satisfied
against. An append-only store is the wrong container for personal telemetry, and
"we will keep it in a different table" is a convention rather than a boundary.

The marginal question all of this would answer is *which of the skills we already
know are installed are actually used*. That is a curation question. It does not
justify a per-developer behavioural record inside a security control's records.

## Options considered

**1. A bespoke ingestion endpoint on the gateway.** The literal reading of the
issue. Rejected. It would be the gateway's **first inbound write path from
consumers** — today consumers only read, and every write path (registration,
approval, publication, the machine API) is an operator or publisher behind OIDC or
a deliberately minted scope. Authentication has no good answer inside the stated
boundary: the facade is PATs only, the web surface is OIDC only, and the machine
API credential (GW_AUTH_0020–GW_AUTH_0024) was designed for pipelines, minted per concern
over an allowlist. A telemetry write scope would have to be handed to every
developer workstation — a standing gateway write credential on every measured
machine, which is the forgery premise above and a large increase in what one lost
laptop reaches. The volume characteristics are the other half: the gateway's write
rate today is bounded by approvals and fetches; this would bound it by how much
the fleet types, on the same PostgreSQL the ledger is on. ADR 0008 recorded that
the facade's availability *is* a security property because it is the only door.
Taking on an endpoint whose load is set by the measured population, and whose
credential that population holds, is an availability risk to that door bought with
a curation metric.

**2. Ingest through the existing machine API scope.** Reuses a designed
credential instead of inventing one, and is worse for it: it stretches a
credential intended for a small number of deliberate pipeline principals across
the whole workstation fleet, and puts a write scope in the same token family as
`adoption:read`.

**3. Pull, aggregate-only, from the organisation's metrics backend.** The gateway
holds a read credential and queries; no inbound surface, no personal data at rest,
the direction of trust inverted. On every axis above this is the better shape, and
it is rejected **only because there is nothing to pull**: for plugin-delivered
content the series is keyed `"third-party"`. Recorded here so it is not
re-derived from scratch — this is the option to revisit if the upstream
identifier changes.

**4. Route client telemetry to the standard OTel pipeline and correlate there.**
Chosen. See below.

**5. Do nothing.** Rejected, but not by much: the boundary is worth writing down
whatever else happens, and the presence half is buildable, unforgeable and useful
on its own.

A note on a tempting shortcut: `arconia-dev-services-lgtm` under the
`observability` profile is a **development** stack for the gateway's own
instruments (GW_OBSERVABILITY_0003 — *Always-recorded gateway metrics and observations*). A
Grafana container the build starts for local work is not a fleet ingest and must
not be reached for as one.

## Decision

**1. The gateway does not accept client-reported telemetry.** No inbound
endpoint, no protocol, no surface. No figure the gateway reports is derived from
anything a client asserted. This is a standing boundary, not a sequencing
statement.

**2. Client telemetry goes where it already goes** — the organisation's
collector, whose destination managed settings already lock. If an organisation
wants invocation correlated with inventory, that join happens in its metrics
backend, against a gateway export, not inside the gateway.

**3. The gateway supplies the half only it can produce: presence.** Which skills
and plugins a served snapshot contains, therefore which identities hold them,
derived from the commit tree the snapshot pins and the fetch ledger — and
exported carrying the identifiers a backend would join on.

The OpenSpec change `invocation-adoption-metrics` proposes the third part as
GW_0213, GW_0214 and the boundary as GW_0215. Nothing here is implemented by this
ADR.

## What presence is, and is not

Stated plainly because the temptation to read it as a finer adoption number is
strong: **a git fetch is all-or-nothing.** Every identity that fetched a snapshot
received every skill in it. Skill-level fetch counts are therefore uniform across
a snapshot's contents *by construction* — a presence statement, never an interest
or usage one — and the report has to say so on its face rather than in a footnote,
or it will be read as the invocation measure it was built instead of.

What it buys is not a better adoption percentage. It is the recall query
[architecture.md §9](../manual/architecture.md) already names — *all identities
that hold `skill-x`* — answered directly, instead of an operator first working out
which marketplaces at which SHAs contained it. That is the query an advisory feed
about a malicious skill needs, and it is the one that has to be right.

## The upstream ask

The measurement #85 wants is blocked upstream, not here. An enterprise
self-hosted marketplace is third-party to the client, so its skill and plugin
names arrive redacted and its marketplace name does not arrive. The ask to press
the vendor on is **an identifier for enterprise-configured marketplaces that
survives redaction** — a name the enterprise itself configured, or a stable hash —
so invocation can be attributed inside the enterprise without the client naming
third-party content to anyone. It belongs beside the client-enforcement ask
already carried in [architecture.md §14](../manual/architecture.md).

## Consequences

- **Issue #85 is not closed by this.** The invocation half is refused, the
  presence half is proposed, and adherence was never in scope. The issue stays
  open as the record of the ask.
- **[architecture.md §9](../manual/architecture.md)'s install-inventory bullet
  becomes wrong** — it offers enrichment "optionally … with client OTel
  telemetry". It is rewritten when this ADR is accepted, not before.
- **Adherence is out of scope permanently, and for a reason stronger than
  difficulty.** It is unobservable in principle from here: the gateway sees a
  repository being read, and everything downstream of that read happens on wires
  it does not touch. Whether guidance was followed is evidenced by an artefact —
  a diff, a passing check — produced by the system doing the enforcing. That is
  CI and policy gates in consuming repos.
- **A negative requirement is a maintenance obligation.** GW_0215 has to be
  something changes are checked against, or it decays into a sentence nobody
  reads. It is written as a requirement rather than a paragraph here for exactly
  that reason.
- **`arconia.otel.enabled` and GW_OBSERVABILITY_0003 are untouched.** The gateway keeps
  recording and optionally exporting its *own* instruments; this ADR is about
  data flowing the other way.

## What would reopen this

- **The upstream identifier lands.** If a self-hosted marketplace's skills become
  nameable in client telemetry, option 3 — an operator-configured, aggregate-only
  pull from the organisation's own backend — is the shape to reconsider, and it is
  still not an ingestion endpoint.
- **A per-identity attestation over telemetry.** If a client can sign a datapoint
  with a credential the gateway issued, the forgeability argument changes and this
  ADR should be superseded rather than cited.
- **A use case that genuinely needs per-user invocation data** and is not recall.
  None was found; one would be a real argument.
