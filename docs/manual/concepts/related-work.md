# Related work

Skills Gateway is not the only product occupying the "central place for AI
agent skills" space. This page positions it against the closest alternatives,
so the difference in trust model is explicit rather than implied by the name.

_Every product on this page is under active development, and several are in
preview. Each section states the date its claims were read from the linked
primary source; treat anything undated as unverified, and re-read the
vendor's own documentation before deciding on it._

## LiteLLM Skills Gateway

*Claims below read from the linked documentation on 2026-09-02.*

[LiteLLM's Skills Gateway](https://docs.litellm.ai/docs/skills_gateway) is a
centralized **registry** for Claude Code skills: teams register a GitHub URL
via API or UI, the system auto-detects the skill name (including nested
subdirectories), an admin flips an *enable* endpoint, and the skill appears on
an unauthenticated hub page and in a generated marketplace manifest that
Claude Code clients add with `/plugin marketplace add`. Skills carry semver,
descriptions, keywords, and a domain/namespace hierarchy.

It solves the **discovery and distribution** half of the problem. It is a
registry of *pointers*: the content authority remains the upstream repository,
and "publishing" gates the visibility of a listing, not the bytes a client
receives.

## agentgateway

*Claims below read from the linked documentation and repository on
2026-09-02.*

[agentgateway](https://agentgateway.dev/docs/about/) —
[source](https://github.com/agentgateway/agentgateway) — is not a registry at
all, and including it here is a way of showing where the boundary of this
comparison lies. It is an open-source **data-plane proxy** for agentic traffic,
donated to the Linux Foundation and now an Agentic AI Foundation project. It
sits on the request path between agents and the things they call: MCP tool
servers, LLM inference endpoints, agent-to-agent (A2A) peers, and ordinary
HTTP/gRPC services.

Its [MCP documentation](https://agentgateway.dev/docs/mcp/) describes
multiplexing several backend MCP servers behind a single endpoint (a "virtual"
MCP backend), OAuth 2.0 and JWT authentication for those servers, CEL-based
authorization rules over individual tools and resources, external
guardrail/processing hooks, and per-request metrics, logs and traces.

What it does **not** do is hold content. There is no artifact, no snapshot, and
no publishing decision: a route points at a live backend, and whatever that
backend returns at call time is what the agent gets. Governance in agentgateway
is enforcement *at invocation* — who may call which tool, under which policy —
rather than curation *before distribution*. Nothing in its documentation
describes an approval workflow over the content a backend serves.

That makes agentgateway complementary to Skills Gateway rather than an
alternative to it: one governs the call, the other governs the bytes. The rows
in the table below that concern publishing and rug-pull are therefore not
gaps in agentgateway so much as questions it does not attempt to answer.

## AWS Agent Registry

*Claims below read from the linked documentation on 2026-09-02.*

[AWS Agent Registry](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/registry.html)
is a managed catalog for publishing and discovering MCP servers, agents,
skills and custom resources across an organization.

!!! warning "The namespace is moving"

    As of 2026-09-02 the documentation carries a migration banner: the service
    has launched under a new `agent-registry` namespace, and support for the
    public-preview `bedrock-agentcore` namespace is stated to be discontinued
    on **2026-09-17**. Some API names differ between the two
    (`SearchRegistryRecords` became `SearchDiscoverableRegistryRecords`; the
    `ListDiscoverableRegistryRecords` and `BatchGetDiscoverableRegistryRecord`
    APIs exist only in the new namespace). The links on this page point at the
    documentation as it stood on that date and may themselves move.

A record has a
[record type](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/registry-supported-record-types.html)
— `AGENT`, `MCP`, `SKILL` or `CUSTOM` — and a matching descriptor that the
registry schema-validates: MCP records against the `server.json` definition
from the official MCP registry, agent records against the A2A AgentCard
specification, skill markdown against the AgentSkills specification.

It is, like LiteLLM's, a **registry of pointers** — and the documentation is
unusually clear about it. A `SKILL` record's descriptor holds an optional
`SKILL.md` and an optional structured definition whose fields are a repository
URL and a list of package identifiers. The same page states plainly that the
markdown "is only used as metadata for discovery purpose" and that the
"Registry does not support storing other agent skill files". The registry
catalogs the description of a skill; the skill itself lives, and changes,
somewhere else.

Its governance model is the most developed of the pointer registries. The
[record lifecycle](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/registry-record-lifecycle.html)
runs `DRAFT` → `PENDING_APPROVAL` → `APPROVED` or `REJECTED`, with
`DEPRECATED` terminal and irreversible from any state, and status changes
carry a `statusReason`. Editing an approved record produces a new `DRAFT`
revision while the approved revision stays discoverable until the new one is
approved — a genuinely careful piece of design, and the closest thing in this
comparison to Skills Gateway's held updates. Discovery APIs and the per-registry
MCP endpoint return only approved revisions; management APIs return the latest
revision whatever its status. Submission raises an EventBridge event so an
existing security or compliance pipeline can call `UpdateRegistryRecordStatus`
to decide; administrators may also enable auto-approval outright. Inbound
authorization for search and the MCP endpoint is IAM or JWT from a corporate
IdP, while control-plane operations are always IAM.

Two limits are worth stating plainly, both from the absence of a claim rather
than the presence of one. First, the approval reviews a *descriptor*, not
content: beyond schema conformance, no scanning or vetting of the referenced
repository or package is described, and because the registry stores no files,
the approved revision does not pin the bytes a consumer will later fetch. The
dual-revision behaviour protects the *record* from a rug-pull, not the
artifact. Second, the
[personas](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/registry-concepts.html)
(administrator, publisher, curator, consumer) are separated by IAM permissions
the administrator configures; nothing in the documentation requires the
approver to be a different principal from the publisher, and the administrator
persona is explicitly described as holding both. Separation of duty is
available to configure, not enforced by the service.

On audit, the
[CloudTrail page](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/registry-cloudtrail.html)
states that "control plane API calls are logged in AWS CloudTrail" and are
"logged as management events by default". No data-plane logging of discovery or
consumption is documented — so who *changed* the catalog is recorded, while who
*read* it is not, at least not by default.

## JFrog AI Catalog and Agent Skills Registry

*Claims below read from the linked documentation and product pages on
2026-09-02.*

JFrog does not ship a product called "AI Registry". The governance product is
the [JFrog AI Catalog](https://jfrog.com/ai-catalog/), within which the
[MCP Registry](https://jfrog.com/ai-catalog/mcp-registry/) and the
[Agent Skills Registry](https://jfrog.com/ai-catalog/skills-registry/) are
capabilities alongside model governance and shadow-AI detection.

This is the one comparison on the page where the other product also **holds
content**, and it is the most direct architectural counterpart to Skills
Gateway. The
[AI Catalog architecture](https://docs.jfrog.com/ai-ml/docs/jfrog-ai-catalog-architecture)
describes the catalog as a governance layer over JFrog Artifactory: Artifactory
stores the model packages, container images and MCP server packages, while the
catalog holds metadata records — including an ML-BOM — that point at those
versioned artifacts. The distinction from AWS and LiteLLM matters: the pointer
resolves into an artifact repository the enterprise controls, not into a
third-party repository it does not.

The Agent Skills Registry applies the same model to skills. JFrog describes a
skill as a versioned bundle of `SKILL.md`, scripts, documents and other assets,
and states that in the registry "every skill is automatically versioned,
scanned for malicious intent, cryptographically signed, and access-controlled",
with "strict approval workflows, ensuring agents only utilize skills that have
been formally vetted for specific projects or teams". The MCP Registry page
similarly describes treating MCP servers as immutable binary artifacts, JFrog
Xray scanning for vulnerabilities, malicious content and license risk, and
JFrog Curation blocking on policy. Consumption runs through **Agent Guard**, a
local proxy that enforces authentication and project-scoped permission checks
on the developer's machine — so the enforcement point is client-side and
per-call, not a server-side facade.

Where Skills Gateway pins a snapshot and refuses to serve anything else, JFrog
scans and signs an artifact and blocks on policy at consumption. Both keep the
bytes; the mechanisms differ. The automated scanning story is a real capability
Skills Gateway does not have — its gate is a human approving a snapshot, with
no content scanner in the loop.

Two caveats. The public pages are marketing pages, and several claims — the
exact approval state machine, what is audited, how remote-endpoint MCP servers
(as opposed to packaged ones) are pinned — are not stated at the level of
detail the AWS documentation offers; treat them as advertised rather than
verified. And the Agent Skills Registry was announced in 2026 in partnership
with NVIDIA; the architecture documentation lists the skills registry as a
planned feature, and no GA date is stated on the pages read here. Confirm its
current availability with JFrog before comparing it as a shipping product.

## The difference in one table

Read the columns as answers to the same question, not as scores; agentgateway
in particular is a different layer and several rows do not apply to it.

| Concern | Skills Gateway | LiteLLM Skills Gateway | agentgateway | AWS Agent Registry | JFrog AI Catalog |
| --- | --- | --- | --- | --- | --- |
| What is registered | The upstream URL, ingested into a quarantine repository | A pointer to an upstream GitHub URL | Nothing — a route to a live MCP/A2A/LLM backend | A metadata record: a schema-validated descriptor plus an optional repository URL or package identifier | Metadata over a versioned artifact stored in Artifactory |
| What clients receive | Bytes from a SHA-pinned, human-approved snapshot served by the read-only facade | Content fetched from upstream at install time | Proxied protocol traffic, live from the backend | Descriptor metadata from search; the skill itself is fetched elsewhere | The artifact from Artifactory, through Agent Guard or a package client |
| Publish gate | Human approval of a specific snapshot; held updates never displace the approved one | Listing visibility (enable/disable) | None — a route is a configuration change | Curator approve/reject with a `statusReason`; auto-approval is a per-registry option | Project-scoped approval, plus Curation policy at the repository perimeter |
| Rug-pull protection | The core of the design: [snapshots and held updates](snapshots-and-ledger.md) | None — upstream refs stay mutable and authoritative | None — the backend is live and mutable by design | Partial: an edit becomes a new `DRAFT` while the approved revision stays discoverable — but the referenced repository or package is outside the registry's control | Strong for what Artifactory stores (immutable, signed artifacts); not documented for remote-endpoint MCP servers |
| Revocation | Revocation unpublishes the served ref itself | Disable the listing; already-installed content unaffected | Remove the route or policy; effective for new calls | Deprecate (terminal, irreversible) or reject to hide from discovery; already-fetched content unaffected | Unapprove or block via Curation; Agent Guard refuses at call time |
| Audit | Append-only ledger of every fetch and administrative action | Not described | Per-request OpenTelemetry metrics, logs and traces | CloudTrail management events for the control plane; no data-plane consumption logging documented | Platform audit and ML-BOM provenance; not detailed on the public pages |
| Facade access | PAT-only facade — a deliberate [trust boundary](trust-boundaries.md) | Unauthenticated public hub | JWT/OAuth/API key with CEL authorization, enforced by the proxy | IAM or JWT for search and the MCP endpoint; control plane always IAM | JFrog Platform credentials, enforced client-side by Agent Guard |
| Single catalog | The [virtual catalog](../guides/virtual-catalog.md), strictly derived from approved-and-served snapshots, self-contained relative sources only | Generated manifest of external pointers | One endpoint multiplexing many MCP backends — no catalog of content | One registry with hybrid search and a native MCP endpoint | One system of record across models, MCP servers and skills |
| Skill metadata for discovery | Marketplace-granular catalog; skill-level browse is not a current capability | Semver, keywords, domains/namespaces per skill | Not applicable | `SKILL.md` plus a structured definition, with hybrid semantic and keyword search | Versioned skill packages with scan and signature status |

## What this means

The four products divide along two axes: whether they hold the bytes, and
whether anything must be approved before a consumer gets them.

**agentgateway holds nothing and approves nothing** — deliberately. It is a
data plane, and its contribution is policy at the moment of invocation. It
composes with any of the other three rather than replacing them.

**LiteLLM and AWS Agent Registry hold metadata and approve metadata.** They
answer "where do I find skills?" extremely well, and AWS answers "who said this
listing was acceptable?" with real rigour — the dual-revision behaviour and the
mandatory `statusReason` are better than most. But a registry of pointers
inherits the [threat model](../index.md#threat-model-in-brief) unmitigated: a
compromised or mutated upstream flows straight to every consumer, because
nothing sits between the listing and the clone. AWS's own documentation is
explicit that the registry does not store skill files, which is precisely why
its approval cannot bind the bytes.

**JFrog and Skills Gateway hold the bytes and gate them**, by different
mechanisms — automated scanning and signing over immutable artifacts on one
side, human approval of a SHA-pinned snapshot served through an authenticated
facade on the other. This is the comparison worth thinking hardest about,
because the disagreement is genuine rather than a matter of scope.

Two capabilities the comparison surfaces as honest gaps on this side:

- **Skill-level discovery metadata** — categories, keywords, per-skill search
  in the portal. AWS's hybrid semantic search and LiteLLM's keyword/domain
  hierarchy are both ahead here. It is a product feature, not an architectural
  difference, and it composes cleanly on top of the existing model if the
  estate grows large enough to need it.
- **Automated content scanning** — JFrog scans and signs on upload; the gate
  here is a human reading a diff. Nothing in the architecture prevents a
  scanner from being added as an input to the approval decision, and the
  append-only ledger is the right place to record what it found, but that is
  not a capability today.

What none of the three alternatives offers is the combination this project is
built around: a snapshot the gateway itself holds, an approval that binds that
exact snapshot, a facade that will serve nothing else, and a ledger that
records every read as well as every decision.
