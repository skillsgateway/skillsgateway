# ADR 0011 — External plugin sources: admission before resolution

*Accepted, 2026-09-02.*

## Context

Design principle 4 of the [architecture](../manual/architecture.md) is "rewrite,
don't just mirror": ingestion resolves every transitive plugin source and
rewrites `marketplace.json` so **every URL a client will ever dereference
resolves inside the gateway**. That is what kills threats T3 (rug pulls) and T4
(transitive sources) for plugin content, not only for the marketplace repository.

It has never been implemented. Phase 1 chose the other fail-closed answer —
**rejection** — and GW_0003 states it: a snapshot whose manifest declares any
source other than a repository-relative path is rejected. `ManifestPolicy`
implements it with a string-shape check, which refuses an object-form source
before its type discriminator is ever read.

Issue [#17](https://github.com/skillsgateway/skillsgateway/issues/17) lifts that
restriction. Its design, and an external security architecture review folded into
it, together describe a large piece of work: a typed source model, a
configuration gate, a resolver fetching external repositories into quarantine, a
rewriter synthesising one deterministic composite commit, a closure recorded as
an immutable domain object, and a substantial network-hardening programme.

Three things about that work are architecture-level rather than feature-level,
which is why they get an ADR rather than living only in a change's `design.md`:

1. It reverses a stated posture (local-only, fail-closed by rejection) that
   requirements, documentation and the compatibility reference all assert.
2. It introduces a **new class** of exposure — outbound network requests driven
   by attacker-influenced manifest content.
3. It changes what a snapshot's SHA *is*, and every trust-boundary component in
   the system reads that SHA.

## Decision

### 1. The reversal is staged, and the stages are separated by an invariant

External plugin source support lands in increments. The first increment is
**admission**: parse sources into a typed model, decide admissibility from
configuration, and ship it behind `enabled: false`. The second is
**resolution**: fetch the admitted sources into quarantine and rewrite the
manifest into a composite snapshot.

Between them, a state exists that has no name in the current model: a source the
gateway understands and accepts in principle but cannot serve. **Held** is the
state that makes a snapshot approvable and therefore publishable, so:

> A snapshot is placed in the held state only when every plugin source its
> manifest declares resolves inside the snapshot the gateway itself serves.

That is GW_0152, and it is stated as a standing invariant over the held state
rather than as a property of one release. An admitted-but-unresolved source is
recorded `rejected`, exactly as an inadmissible one is, with a violation that
distinguishes the two — the operator actions differ. The invariant is what makes
shipping admission alone safe: T4 stays closed for the whole duration of the
staged reversal, by construction rather than by schedule. It also keeps being
true for every source type added later, including one nothing can resolve yet.

*Rejected:* shipping admission such that an admitted source produces a held
snapshot with the external URL still in the served manifest. That is a published
marketplace instructing clients to clone a repository the gateway never saw — T4,
reopened, for however long the resolver takes.

### 2. Snapshot identity becomes the served composite SHA

When the rewriter lands, `snapshots.sha` is the **synthesised composite commit**
— the commit that is actually served — with the upstream commit recorded
alongside it as `upstream_sha` and retained as the composite's parent so the
unrewritten manifest stays byte-exact and reachable as evidence.

The reason is that `snapshot.sha()` is what `VettingService` opens, what
`ApprovalService` copies and publishes, what the facade advertises, what
retention anchors on and what the ledger records. Making the served commit the
snapshot's identity means **none of them learns that external content exists**;
vetting sees the closure because the closure *is* the commit, and the approved
SHA is the served SHA with nothing in between.

*Rejected:* keeping `snapshots.sha` as the upstream SHA and adding a nullable
`served_sha`. It breaks the meaning of `UNIQUE (marketplace_id, sha)` — the same
upstream commit with different resolved external heads is genuinely different
served content — and it forces five downstream readers, all of them at or behind
a trust boundary, to each pick the right column.

*Rejected:* rewriting at publish time in `ApprovalService`. The reviewer would
approve content that is not what gets served, and vetting would never see the
external content at all.

*Rejected:* mirroring each external repository as its own facade repository and
rewriting sources to facade URLs. It multiplies published repositories, approval
copies, and per-mirror scope and credential semantics, and the client then needs
credentials for each URL. Grafting into one composite commit keeps one approval,
one served repository, one audit trail. Cross-marketplace object dedupe via a
shared object store or git alternates is **not** a later refinement of this:
`published/{name}.git` being physically separate repositories is currently what
makes a marketplace-scoped token unable to reach another marketplace's objects,
and `upload-pack` want-checking is not an authorization layer. Any move to a
shared store must solve per-object authorization first.

### 3. Network egress isolation is the primary SSRF control; URL validation is
defence in depth

Once the resolver exists, manifest content — attacker-influenced upstream data —
drives gateway-originated fetches. The scheme allowlist, the host allowlist and
private-address refusal are **second-layer** controls. The primary control is
deployment topology: ingestion egress routed through a proxy or DMZ with no route
to cloud metadata endpoints, internal APIs, or anything holding corporate
credentials. The gateway provides the configuration and documents the topology;
the network enforces it.

The in-application layer, when the resolver lands, validates **after** DNS
resolution against every resolved address, connects to the validated address so a
second resolution cannot substitute a private target, re-runs the check after
each redirect, refuses HTTPS→HTTP downgrade, and bounds redirects, ports,
timeouts and pack inflation.

This ADR records that ordering now, at the point the direction is chosen, so that
the admission increment's allowlists are not mistaken for having addressed SSRF.
They have not; there is no SSRF surface until the resolver ships.

### 4. `npm` and `archive` sources are refused permanently

Not "not yet". Package-manager content belongs to the repository manager the
gateway complements rather than duplicates (architecture principle 6), and an
archive has no commit identity to pin, so neither can satisfy the pinned,
content-addressed snapshot model everything downstream is built on. They are
modelled in the typed source hierarchy — so a refusal can name them — and refused
above the configuration gate, where no setting can reach them.

### 5. Admission is deployment configuration, not estate state

Enablement is global gateway configuration of the same kind as
`skills-gateway.vetting.license`, defaulting to disabled. It is deliberately not
a per-marketplace, API-managed property, so it introduces no new estate object
type and the declarative-estate obligation is answered rather than deferred. The
host allowlist provides the per-upstream control an operator actually reaches
for.

## Consequences

- GW_0003 evolves rather than being contradicted: local-only rejection becomes
  the **default-configuration** behaviour (revision 0.2.0), and SVC_GW_0003 is
  unchanged — it is now also the regression test pinning that default. A gateway
  that is never configured for external sources behaves exactly as it does today.
- The compatibility reference's "rejected fail-closed" row gains the
  default-configuration qualifier; it is not a breaking API change, as no
  endpoint or payload shape moves.
- The staged plan leaves one honest rough edge: an operator who enables the flag
  before the resolver exists gets rejections with a clearer message, not working
  external plugins. The violation text and the configuration reference say so
  plainly. A flag that silently did nothing would be worse; a flag that served
  unresolved content would be unsafe.
- Determinism of the composite commit is only a durable claim if the
  transformation is itself an input, so the rewriter will carry an explicit
  transformer version and the admission policy will hash to a policy version,
  both stamped into the composite commit. A version bump therefore produces a new
  SHA that goes back through vetting and approval — the correct behaviour, not an
  inconvenience.

## Status of the increments

| Increment | Requirements | State |
| --- | --- | --- |
| Admission: typed source model, configuration gate, held-only-if-local invariant | GW_0003 (rev. 0.2.0), GW_0150, GW_0151, GW_0152 | This change |
| Resolution: closure fetch, deterministic composite rewrite, closure provenance, closure-wide vetting, full-closure approval invariant | to be assigned | #17, next |
| Hardening: egress proxy, post-DNS and per-redirect address validation, inflation and size budgets, cycle detection, global deadline, negative cache | to be assigned | #17, with or after resolution |
