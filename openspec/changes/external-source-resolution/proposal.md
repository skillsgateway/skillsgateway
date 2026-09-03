# Proposal: external-source-resolution

## Why

Increment 1 of issue [#17](https://github.com/skillsgateway/skillsgateway/issues/17)
([#244](https://github.com/skillsgateway/skillsgateway/pull/244), ADR 0011) shipped
the typed source model and configuration-gated *admission*, and stopped
deliberately before any outbound fetch: "no new outbound network surface exists
after this change". It left exactly one named branch point — the `Admitted` arm
of `ManifestPolicy.validate` — and one standing invariant, **GW_0152**: a
snapshot is held only when every source it declares resolves inside the snapshot
the gateway serves. Until something can resolve, that arm returns a violation and
an admitted source is a *rejected* snapshot.

This change fills that arm. It is the second increment of ADR 0011's staged
reversal: fetch each admitted source into quarantine, graft it into one
deterministic composite commit whose manifest declares only gateway-local
sources, and make that composite the snapshot. GW_0152 then becomes
*structurally* satisfied rather than satisfied by refusal — and it is enforced as
a post-condition, not as a comment: the composite is served only if its own
rewritten manifest passes the same local-only gate that rejected the original.

Creating that outbound surface is the whole risk of this change, so the network
hardening the fetch needs is **in scope here, not deferred**. An increment that
added the fetch and left the address, redirect and inflation policy to a third
increment would ship an unguarded SSRF surface driven by attacker-influenced
manifest content — the exposure class ADR 0011 §3 exists to bound.

## What Changes

- **Resolution (GW_0155).** `ExternalSourceResolver` fetches each admitted
  source into the marketplace's own quarantine repository under a scaffolding
  ref, pinned at the resolved commit, using JGit. Scaffolding refs are pruned
  once the composite exists; the content stays reachable from the composite's
  tree. Only the `github` type resolves (see "Scope cut").
- **Deterministic composite snapshot (GW_0156).** `ManifestRewriter` synthesises
  one commit: the upstream tree, each external plugin's tree grafted under
  `_plugins/<name>/`, and `.claude-plugin/marketplace.json` rewritten so every
  external `source` becomes `./_plugins/<name>`. The composite's **parent is the
  upstream commit**, so the unrewritten manifest stays byte-exact and reachable
  from the served SHA as evidence (ADR 0011 §6 of the issue's review). Snapshot
  identity becomes the served composite SHA (ADR 0011 §2). Same upstream commit
  + same resolved plugin SHAs + same transformer version ⇒ same composite SHA.
- **SSRF-hardened transport (GW_0157).** A gateway-owned JGit
  `HttpConnectionFactory`, installed per fetch (never as JGit's global static),
  validates **after** DNS against every resolved address, connects to the
  validated address so a second resolution cannot substitute a private target,
  re-runs the full check after each redirect, refuses HTTPS→HTTP downgrade and
  cross-host redirects, bounds redirect depth, refuses credentials embedded in a
  URL and non-allowlisted ports, and applies connect/read timeouts. Link-local
  (including `169.254.169.254`), multicast, unspecified and reserved addresses
  are refused under **every** configuration; loopback and RFC1918 only under an
  explicit `allow-private-networks`.
- **Resource budgets (GW_0158).** Received bytes per source and per closure,
  inflated bytes, inflation ratio, object count, largest blob, tree depth, and a
  wall-clock deadline for the whole resolution. Every breach refuses; none
  exhausts the process.
- **Atomic failure (GW_0161).** Any refusal, timeout, budget breach or mid-fetch
  failure records the snapshot at the **upstream** SHA in `rejected`, with no
  composite commit, no graft, no scaffolding ref left behind, and nothing
  published. GW_0152 therefore holds through every failure path: there is no
  held-with-unresolved-sources state to reach.
- **GW_0152 is now enforced twice.** The manifest gate still refuses an admitted
  source it cannot resolve, and the rewriter re-runs that same gate over the
  composite manifest before the commit is used. A composite that still declared
  a non-local source could not be served.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `marketplace-ingestion`: gains resolution of admitted external plugin sources
  into quarantine, a deterministic composite snapshot whose manifest is
  gateway-local, an SSRF-hardened and resource-bounded outbound path for the
  fetches it performs, and the guarantee that any resolution failure leaves the
  snapshot rejected.

## Scope cut, and why

The remaining work on #17 was three increments (ADR 0011's table, restated in the
issue's reopening comment): resolver + rewriter, the closure domain object, and
network hardening. This change is **increment 2 plus the hardening its own fetch
requires**, cut as follows.

**In, because the fetch cannot ship without it:**

- `github` only. `owner/repo` derives its URL from configuration and a
  `[A-Za-z0-9._-]+/[A-Za-z0-9._-]+` shape, so a manifest cannot inject a host at
  all — the strongest position from which to open the first outbound path. `git`
  and `git-subdir` take an arbitrary URL from the manifest and stay out of
  `allowed-types`, so the allowlist never advertises a type nothing resolves.
- The address/redirect/downgrade/port/timeout policy and the byte, object and
  time budgets. These are what make the fetch safe; deferring them would mean
  shipping the surface without them.

**Out, named:**

- **The closure domain object** (`closures`, `closure_nodes`, `closure_edges`,
  `snapshots.upstream_sha`, `original_manifest_blob`) and closure-aware
  re-vetting. No schema change here: the composite's **parent** is the upstream
  commit and the commit message carries per-source provenance, so every fact the
  columns would hold is already recorded in the served commit. The tables earn
  their keep when the blast-radius query needs them, which is the increment that
  wires `RevetService`.
- **`git` and `git-subdir`** (subtree grafting), **`npm` and `archive`**
  (refused permanently, ADR 0011 §4).
- **Declared `ref` / `sha` pinning.** A source that declares either is
  **refused**, naming the field — silently resolving a source at a different
  commit from the one the manifest pinned is worse than refusing it. Honouring
  them is a named follow-up.
- **The egress proxy** (`egress-proxy`), **cycle detection beyond depth 1** (a
  grafted repository's own manifest is not interpreted, so the closure is depth 1
  by construction; identical sources within one manifest are deduplicated), the
  **negative cache**, and **portal provenance badges**.
- **Extending the hardened transport to the marketplace upstream fetch.** That
  URL is admin-supplied at registration and already scheme-checked; a
  manifest-derived URL is attacker-influenced. Different trust level, and folding
  it in would change the behaviour of every existing ingestion path in the same
  PR that opens a new one.

## Impact

- **DB**: none. No migration, no schema change.
- **Backend**: new `ExternalSourceResolver`, `ManifestRewriter`, `SourceAddressPolicy`,
  `GuardedHttpConnectionFactory` and `ResolutionBudget` in
  `dev.skillsgateway.server.ingestion`; `ManifestPolicy.validate` gains a sibling
  that returns the admitted set alongside the violation; `IngestionService`
  evaluates the manifest before pinning, so the pinned ref is the served commit.
  `SkillsGatewayProperties.ExternalSources` gains `github-base-url`,
  `allow-private-networks` and a `budgets` block.
- **API**: none. No endpoint, DTO or OpenAPI change — `SnapshotContentService`
  reads the manifest's textual `source` and walks `<source>/skills/`, so a
  rewritten `./_plugins/<name>` source produces the existing inventory shape with
  no change. Nothing for the compatibility gate to diff.
- **Trust boundary**: this opens the gateway's first manifest-driven outbound
  network path → old-coder Tier 3, adversarial tests are part of the definition
  of done, `evidence.md` ships with the change.
- **Estate**: no new API-managed runtime state. Resolution is governed by the
  same deployment configuration block ADR 0011 §5 already answered the estate
  obligation for.
- **Docs** (same PR): `architecture.md`, `reference/configuration.md`,
  `concepts/trust-boundaries.md`, `concepts/snapshots-and-ledger.md`,
  `reference/compatibility.md`, `guides/approving-snapshots.md`, and ADR 0011's
  increment table.
