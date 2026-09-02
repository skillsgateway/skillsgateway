# Proposal: external-plugin-sources

## Why

`ManifestPolicy.validateSource` rejects any plugin `source` that is not a
textual relative path, which means an object-form source
(`{"source": {"source": "github", "repo": "…"}}`) is refused by the
`!source.isTextual()` branch before its type is ever read. A marketplace whose
manifest declares external plugins is therefore unusable through the gateway,
and design principle 4 of the architecture — "Rewrite, don't just mirror",
resolving transitive sources so **every URL a client dereferences resolves
inside the gateway** — is unimplemented. Issue
[#17](https://github.com/skillsgateway/skillsgateway/issues/17) is Phase 2's
answer to that, and its own triage names the first increment: **typed source
model + config gate + a single `github`-type source, behind `enabled: false`**.

This change is that first increment, and it deliberately stops before the
resolver. The reason is a boundary the issue's design implies but does not
state: between *admitting* an external source and *resolving* it there is a
state in which the gateway understands a source and accepts it in principle but
cannot yet serve its content. Held is the state that makes a snapshot
approvable and therefore publishable, so a snapshot admitted-but-unresolved must
never reach it — otherwise threat **T4** is reopened for exactly as long as the
resolver takes to land. This change turns that into a standing requirement
(GW_0152) rather than a temporary property of one release, which is what makes
shipping admission on its own safe.

## What Changes

- **Typed plugin source model (GW_0150).** `ManifestPolicy`'s string-shape check
  is replaced by a parse into a sealed `PluginSource` — `Local`, `GitHub`,
  `GitUrl`, `GitSubdir`, `Npm`, `Archive` — and admission is decided from the
  parsed type, not from whether the JSON value happened to be a string. Anything
  unparseable, any type the gateway does not implement, every `npm` and
  `archive` source, and any local path escaping the repository are refused
  fail-closed with a violation that names the plugin and the form its source
  took. `npm` and `archive` are refused permanently, not pending: package-manager
  content belongs to the repository manager the gateway complements
  (architecture principle 6), and an archive has no commit identity to pin.
- **Config-gated admission (GW_0151).** A new `skills-gateway.ingestion.external-sources`
  block, `enabled: false` by default, with an admissible-type allowlist, a host
  allowlist, and a `max-sources` cap. A `github` shorthand is expanded to its
  clone URL *before* the checks, and the URL then has to satisfy the same
  `skills-gateway.allowed-url-schemes` allowlist that governs registration — one
  scheme policy for every URL the gateway will ever dereference. Every check is
  decided from the manifest alone, with no network call.
- **Held only if gateway-local (GW_0152).** A snapshot reaches `held` only when
  every declared source resolves inside the served snapshot. An admitted but
  unresolved external source is recorded `rejected` with a violation that reads
  differently from "not admitted" — the operator action differs (configuration
  versus capability the gateway does not have yet).
- **GW_0003 evolves rather than being contradicted** (revision 0.1.0 → 0.2.0):
  the local-only rejection becomes the *default-configuration* behaviour, with
  the admission path named as the exception. SVC_GW_0003 is untouched and is now
  also the regression test pinning that default.
- **ADR 0011** records the architecture-level half: the staged reversal of the
  local-only stance, why admission ships before resolution, that the served
  composite SHA (not the upstream SHA) becomes snapshot identity when the
  rewriter lands, and that network egress isolation — not URL validation — is
  the primary SSRF control.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `marketplace-ingestion`: manifest validation gains a typed source model and a
  configuration-gated admission decision for external plugin sources, defaulting
  to today's local-only rejection; and gains the standing invariant that a
  snapshot is held only while every declared source resolves inside the served
  snapshot.

## Out of scope (named, so the boundary is explicit)

Deferred to the next increment(s) of #17, unchanged by this change: the
`ExternalSourceResolver` and any outbound fetch of external content; the
`ManifestRewriter` and the composite commit; the closure domain object and its
Flyway migration; `git-subdir` subtree handling; the negative cache; the egress
proxy and post-DNS/per-redirect address validation; decompression-bomb budgets;
portal provenance. **No new outbound network surface exists after this change** —
which is the point of cutting the increment here.

## Impact

- **DB**: none. No migration, no schema change.
- **Backend**: new `PluginSource` (sealed interface) and `ExternalSourceAdmission`
  (pure function) in `dev.skillsgateway.server.ingestion`; `ManifestPolicy`
  becomes configuration-aware (see `design.md` — it is `static` today);
  `SkillsGatewayProperties` gains `Ingestion` / `ExternalSources` nested records.
- **API**: none. No endpoint, DTO or OpenAPI change, so no `openapi.json`
  regeneration and nothing for the compatibility gate to diff.
- **Trust boundary**: this is ingestion and the registration allowlist's blast
  radius → old-coder discipline, adversarial/negative tests are part of the
  definition of done, `evidence.md` ships with the change.
- **Docs** (same PR): `architecture.md` (the "MVP scope: local sources only"
  paragraph and the phase roadmap), `reference/configuration.md` (the new keys
  plus a hardening note), `concepts/trust-boundaries.md` (the new SSRF surface
  and the held-only-if-local invariant), `guides/registering-a-marketplace.md`
  (the violation messages), `reference/decisions.md` (ADR 0011).
