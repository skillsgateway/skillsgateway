# Design: external-plugin-sources

## Context

- `ManifestPolicy` is a **static utility with no configuration**. `validate` is
  called from `IngestionService.validateManifest`, itself `private static`, off
  the `ingestLocked` path. Its `validateSource` rejects on `!source.isTextual()`
  before reading any type discriminator, and returns one violation string for
  every non-local form.
- `IngestionService.ingestLocked` maps that violation to state: `violation ==
  null ? HELD : REJECTED`. A rejected snapshot is never vetted and never
  approvable; a held one is exactly what a reviewer may approve and
  `ApprovalService` may publish.
- Registration already enforces `skills-gateway.allowed-url-schemes`
  (`MarketplaceRegistrationService.validate`) on the one URL the gateway
  dereferences today.
- Two different object shapes already appear in the test corpus as "external",
  and both are currently rejected by the same `!isTextual()` branch, so nothing
  has ever had to tell them apart: `{"source": "github", "repo": "stranger/evil"}`
  (`IngestionTests`) and `{"github": "acme/elsewhere"}` (`HostedLifecycleTests`).

## Goals / Non-Goals

**Goals**

- Admission is decided from a **parsed type**, not from a JSON shape check, so a
  later change can admit one type without loosening any other.
- The default configuration is **byte-for-byte today's behaviour**: every
  external source rejected, same outcome, same snapshot state.
- Every admission check is a **pure function of the manifest plus configuration**,
  exhaustively testable without a network or a repository.
- The invariant that keeps T4 closed — *held only if every source is
  gateway-local* — is written down and tested now, while it is cheap, rather
  than discovered later when the resolver makes it load-bearing.

**Non-Goals**

- No resolver, no rewriter, no composite commit, no closure schema, no outbound
  fetch of any kind. **This change adds zero new network surface.**
- No `git-subdir` subtree handling, no negative cache, no egress proxy, no
  decompression budgets. Those belong to the resolution increment, where they
  have something to protect.
- No API, DTO, OpenAPI or portal change.

## Decisions

1. **`ManifestPolicy` becomes an injectable component; the decision stays a pure
   function.** `ExternalSourceAdmission` is a record built from
   `SkillsGatewayProperties` (enabled, allowed types, allowed hosts, allowed URL
   schemes, max sources) with a single method
   `decide(PluginSource) -> Decision` returning `LOCAL`, `ADMITTED`, or
   `REFUSED(violation)`. `ManifestPolicy` holds one and is a `@Component`;
   `IngestionService` takes it by constructor and `validateManifest` stops being
   `static`.
   *Alternative rejected:* keeping `ManifestPolicy` static and passing the
   admission as a parameter down the call chain — it works, but every future
   caller then has to be trusted to pass the *configured* admission rather than a
   permissive default, and a fail-closed gate should not have a convenient
   argument that opens it.
   *Alternative rejected:* reading the properties inside `ManifestPolicy` from a
   static holder — untestable in the way that matters here, which is running the
   same manifest through five different configurations in one test class.

2. **`PluginSource` is a sealed interface, and the unsupported types are members
   of it.** `Local`, `GitHub`, `GitUrl`, `GitSubdir`, `Npm`, `Archive`. `Npm` and
   `Archive` are modelled rather than treated as parse failures precisely so the
   refusal can name them — an operator who declared an npm source deserves "npm
   sources are not supported", not "unparseable source". They are refused by the
   admission function unconditionally, in the branch above the `enabled` check,
   so no configuration can ever admit them.

3. **A textual source remains exclusively a repository-relative local path.**
   This is today's behaviour and this change does not touch it. It carries a
   known ambiguity — a bare `"owner/repo"` is indistinguishable from a relative
   path and is accepted as local — which is recorded as an open question below
   rather than fixed here, because tightening it is a behaviour change to
   manifests that work today and belongs with the increment that walks the tree
   anyway.

4. **Only the discriminated object form is parsed; anything else is refused.**
   `{"source": "<type>", …}` is the shape the parser understands. The
   key-per-type shape (`{"github": "acme/elsewhere"}`) parses as *unrecognised*
   and is refused fail-closed, which is what it already is today. Choosing one
   canonical shape and refusing the other loudly is the fail-closed reading; a
   parser that accepted both would be guessing at intent on the input class that
   matters most.

5. **`github` is expanded to its clone URL before the scheme and host checks.**
   `owner/repo` → `https://github.com/owner/repo`. The checks therefore see the
   URL the gateway would actually dereference, not the shorthand, so a host
   allowlist means what an operator thinks it means. The expansion is the *only*
   place a type-specific URL convention lives.

6. **The URL scheme allowlist is reused, not duplicated.**
   `skills-gateway.allowed-url-schemes` already governs registration; the same
   list governs an external source's derived URL. One scheme policy for every URL
   the gateway will ever dereference — a second, parallel list would be a second
   thing to get wrong, and drift between them would be silent.

7. **An admitted-but-unresolved source is `rejected`, with its own violation
   text.** This is the crux of shipping admission alone. `ingestLocked`'s
   `violation == null ? HELD : REJECTED` mapping is unchanged; the admission
   function returns a violation for an admitted external source too, worded to
   say the source *was* admitted and cannot yet be resolved. Two distinct
   messages because two distinct operator actions: "enable the type" versus
   "this gateway cannot do that yet". When the resolver lands, that branch is the
   single place it plugs into, and GW_0152 becomes structurally satisfied instead
   of satisfied by refusal.
   *Alternative rejected:* letting an admitted source produce a `held` snapshot
   with the external URL still in the served manifest. That is a published
   marketplace telling clients to clone a repository the gateway never saw —
   threat T4, reopened, for the duration of the next increment.

8. **`max-sources` counts external sources, not plugins.** A local-only manifest
   with a thousand plugins is not the thing being bounded; the bound exists
   because each external source will eventually become a fetch.

9. **Configuration is global, not per marketplace.** Per-marketplace opt-in would
   make admission API-managed runtime state and pull in the declarative-estate
   obligation (a sixth `Estate` field, every `new Estate(…)` call site). The host
   allowlist gives most of the per-upstream control an operator wants. Recorded
   here so the estate obligation is answered deliberately: **no new estate object
   type is introduced**, because this is deployment policy of the same kind as
   `vetting.license`, not runtime state an API manages.

## Configuration

```yaml
skills-gateway:
  ingestion:
    external-sources:
      enabled: false                     # default: GW_0003's local-only behaviour
      allowed-types: [github]            # this increment implements github only
      allowed-hosts: []                  # empty = any host; non-empty = exact-host allowlist
      max-sources: 20                    # external sources per manifest
```

`Ingestion` and `ExternalSources` follow the existing nested-record-with-defaults
pattern (`Sync`, `Vetting`, `Catalog`): a `null` block binds to defaults, so an
absent block is the shipped default and an upgrade changes nothing.

## Security argument

**What changes about the threat model.** Nothing, in this increment — and that is
a claim worth making precisely rather than waving at. No code path added here
contacts anything: admission is decided from manifest bytes already in
quarantine plus static configuration. What changes is that the gateway now
*understands* attacker-influenced source declarations well enough to route them,
which is the groundwork for the increment that does fetch, and the bounds it
will fetch under are established and tested before the fetching exists.

**Why the served SHA remains snapshot identity.** Untouched here: no snapshot is
recorded against anything but the upstream commit, because no composite commit
exists. The decision that it *will* move to the composite SHA when the rewriter
lands is recorded in ADR 0011 rather than left implicit, because that is what
keeps vetting, approval, the facade and retention unchanged by construction —
they all read `snapshot.sha()`, so making the served commit the snapshot's
identity means none of them learns that external content exists. The alternative
(keeping `snapshots.sha` upstream and adding a nullable `served_sha`) would force
every one of those readers to choose a column, which is five chances to choose
the wrong one at a trust boundary.

**Host allowlist.** Empty by default *and* meaningless while `enabled: false`. It
is exact-host matching, applied to the derived clone URL after `github`
expansion, and it is the operator's lever against threat T5 (typosquatting) and
against a manifest naming an internal host. It is deliberately not a suffix or
pattern match: `evil-github.com` must not satisfy an allowlist entry of
`github.com`, and a pattern language is a second thing to get subtly wrong.

**Max-sources bound.** The cap on external sources per manifest bounds the work
one hostile manifest can cause in the increment where each source becomes a
fetch. Introducing it now, with tests, means the resolver inherits a bound
rather than adding one.

**What this change deliberately does not claim to control.** Application-level
URL validation is defence in depth, not the primary SSRF control. The primary
control is network egress isolation — an ingestion path with no route to cloud
metadata endpoints or internal APIs — and that, together with post-DNS address
validation, per-redirect re-validation, downgrade refusal and pack-inflation
budgets, belongs to the resolution increment and is recorded as such in ADR 0011.
Shipping the allowlists now must not be read as having addressed SSRF; there is
no SSRF surface yet to address.

## Risks / Trade-offs

- **[An operator enables the flag and gets rejections instead of resolution]** →
  the violation says so in as many words, and the configuration reference states
  that this increment admits sources without resolving them. The alternative —
  a flag that silently does nothing — is worse; the alternative that serves the
  content is unsafe.
- **[Parsing more shapes widens the input surface]** → the parser is total: every
  input maps to a `PluginSource` variant or to a refusal, there is no fall-through,
  and the sealed hierarchy makes the switch exhaustive at compile time.
- **[Refusing the key-per-type object shape rejects a manifest a client might
  accept]** → it is rejected today too, so this is not a regression; and a
  rejected snapshot is a reviewable, diagnosable state, not a silent one.

## Migration Plan

None. No schema change, no data migration, no API change. The default
configuration reproduces current behaviour exactly, which is what SVC_GW_0003 —
unchanged — now also pins.

## Open Questions (Decisions to confirm)

1. **Bare-string GitHub shorthand.** `"source": "owner/repo"` is accepted as a
   relative path today and still is after this change. Proposed resolution, in
   the resolver increment: require a local path to exist in the snapshot tree,
   which disambiguates structurally and costs nothing there because the tree is
   already being walked. Confirm that rather than tightening the string form now.
2. **`allowed-types` in this increment.** Defaulted to `[github]` since that is
   the only type the next increment will resolve. Should `git` and `git-subdir`
   be listed as admissible now (and rejected by GW_0152 as unresolvable), or
   stay out of the allowlist until they can be resolved? Proposed: out, so the
   allowlist never advertises a type nothing implements.
3. **Requirement id block.** GW_0150–GW_0152 claimed; GW_0149 is the highest in
   `requirements.yml` and no open PR claims further ids. The issue's provisional
   GW_0105+ block is superseded — those ids are taken.
