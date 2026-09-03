# Design: external-source-resolution

This is the executable specification for the change. It is the artifact to review
in place of the implementation (`.claude/skills/old-coder`, Tier 3: this opens
the gateway's first manifest-driven outbound network path).

## The requirements this change works against

Named here once so every bare id below resolves to something. The text itself
lives only in `docs/reqstool/requirements.yml`.

| Id | Title | Role here |
| --- | --- | --- |
| GW_0155 | Resolution of admitted external plugin sources into quarantine | added |
| GW_0156 | Deterministic composite snapshot with a gateway-local manifest | added |
| GW_0157 | Address, redirect and transport policy for source resolution | added |
| GW_0158 | Resource-bounded source resolution | added |
| GW_0161 | A failed resolution leaves the snapshot rejected and nothing half-resolved | added |
| GW_0152 | No snapshot is held while a plugin source is not gateway-local | unchanged; now satisfied by construction rather than by refusal |
| GW_0151 | Configuration-gated admission of external plugin sources | unchanged; still decides admission with no network call |
| GW_0150 | Typed plugin source model | unchanged, extended with a refusing variant for a declared pin |
| GW_0003 | Local-only plugin sources unless external sources are enabled | unchanged; still the shipped default |
| GW_0137 | An ingestion reports a pinned snapshot only when it is pinned | unchanged; the pin now names the served commit |

## Context

- `ManifestPolicy.validate(byte[]) -> String` is the gate. `null` is what
  `IngestionService.ingestLocked` maps to `held`; anything else maps to
  `rejected`. Its `Admitted` arm currently returns a violation saying the source
  was admitted but cannot be resolved — the single branch point increment 1 left.
- `IngestionService.ingestLocked` today: fetch upstream into
  `refs/quarantine/incoming`, **pin `refs/snapshots/<upstream sha>`**, dedupe on
  `(marketplace, sha)`, validate the manifest, insert the row, vet if held.
- `ExternalSourceAdmission.decide` already returns `Local` / `Admitted(source,
  cloneUrl)` / `Refused(violation)` from configuration alone, with no network
  call, and `npm`/`archive` refused above the `enabled` branch.
- `PluginSource.GitHub.cloneUrl()` hard-codes `https://github.com/` and
  validates `owner/repo` against `[A-Za-z0-9._-]+/[A-Za-z0-9._-]+`.
- `CatalogService.commitCatalog` is the precedent for synthesising a commit with
  `ObjectInserter` / `CommitBuilder`, and `pruneInternalRefs` for removing
  scaffolding refs afterwards.
- `SnapshotContentService.plugins` reads the manifest's textual `source` and
  walks `<source>/skills/`. `VettingService` opens `snapshot.sha()`.
  `ApprovalService` copies `refs/snapshots/<sha>`. Nothing reads an upstream SHA.

## Goals / Non-Goals

**Goals**

- An admitted `github` source becomes content the gateway serves, with every URL
  a client dereferences resolving inside the gateway (architecture principle 4).
- GW_0152 survives every path: no failure mode reaches `held` with an unresolved
  source. It is enforced structurally, by re-running the same gate over the
  rewritten manifest, not by a comment.
- The outbound path is bounded in address space, redirects, bytes, objects and
  time before it is used for the first time.
- Everything downstream — vetting, approval, the facade, retention, the content
  inventory, the diff — is unchanged **by construction**, because they all read
  `snapshot.sha()` and that is the composite.

**Non-Goals**

- No schema change, no closure tables, no API change, no portal change.
- No `git`, `git-subdir`, `npm` or `archive` resolution.
- No egress proxy, no negative cache, no recursion past depth 1.
- No change to the marketplace upstream fetch.

## Decisions

1. **The manifest is evaluated before the snapshot ref is pinned.** Today the pin
   precedes validation. Once resolution exists, the pinned ref must be the
   *served* commit, and which commit that is is only known after resolution. So
   `ingestLocked` becomes: fetch upstream → evaluate → (resolve → rewrite) → pin
   the served commit → dedupe → insert → vet. The upstream commit stays reachable
   throughout via `refs/quarantine/incoming`, and permanently via the composite's
   parent, so nothing is unreachable at any point.
   *Consequence, deliberate:* `refs/snapshots/*` in quarantine keeps its
   one-to-one correspondence with snapshot rows, which retention depends on. A
   design that pinned the upstream commit *and* the composite would leave an
   orphan ref no retention pass ever reclaims.
   *Alternative rejected:* pinning upstream first and deleting it when a
   composite appears — it breaks when a *previous* ingest of the same commit was
   rejected under a stricter configuration and its row still names the upstream
   SHA.

2. **`ManifestPolicy` grows `evaluate`, and `validate` becomes its violation
   projection.** `evaluate(byte[]) -> Evaluation(String violation, JsonNode
   manifest, List<Admitted> admitted)`. `validate` stays, delegating, because it
   is the gate `ManifestPolicyTests` pins and the shape the rewriter's
   post-condition re-uses. Refusals short-circuit exactly as today, so the
   default configuration produces byte-identical violation strings.

3. **A declared `ref` or `sha` on an external source is refused, not ignored.**
   The architecture says plugin entries may carry `ref` and a 40-char `sha`, with
   `sha` winning. This increment resolves at the remote's default branch head
   only. Silently resolving at a *different* commit from the one a manifest
   pinned is a trust defect — the operator asked for a specific commit and would
   get another — so the source is refused with a violation naming the field.
   *Alternative rejected:* honouring `sha` by fetching everything and then
   resolving it. Fetching all refs to find one commit is unbounded work for a
   feature that can wait for its own increment.

4. **One composite commit, parented on the upstream commit.** Root tree = the
   upstream tree, plus each external plugin's tree at `_plugins/<name>`, plus a
   replaced `.claude-plugin/marketplace.json`. Built with an in-core `DirCache`
   (`DirCacheBuilder.addTree` for the upstream tree and each graft, a
   `DirCacheEditor` for the manifest blob, `writeTree`) rather than hand-rolled
   `TreeFormatter`s, so canonical entry ordering is JGit's problem and not a
   subtle bug in ours.
   The parent keeps the unrewritten manifest byte-exact and reachable from the
   served SHA forever: `git diff <parent> <served>` is the whole transformation.

5. **Determinism, and what is *not* an input to it.** Fixed `PersonIdent`
   (`skills-gateway <gateway@localhost>`) at epoch zero, UTC; message trailers
   carrying the upstream SHA, each plugin's source URL and resolved SHA in plugin
   order, and `Transformer-Version`. The invariant: *same upstream commit + same
   resolved plugin SHAs + same transformer version ⇒ same composite SHA*, which
   is what keeps `findByMarketplaceAndSha` dedupe working.
   ADR 0011 also names a **policy version** as an input. It is deliberately
   **not** included here, and this is the one place this change departs from the
   ADR: hashing the admission configuration into the commit would give the served
   SHA a dependency on, say, adding an unrelated host to the allowlist, forcing
   re-vetting and re-approval of content that did not change. Recorded as an open
   question for the owner rather than decided quietly.

6. **`_plugins` is a reserved root directory, and every graft hazard fails
   closed.** Refused, each with its own violation: the upstream root tree already
   contains a `_plugins` entry (any mode); an external plugin's name does not
   match `^[a-z0-9][a-z0-9_-]*$` (the facade's marketplace-name pattern — so no
   name can be a path, a traversal or a case-collision); two external plugins
   share a name. Refusal is a rejected snapshot, which is reviewable; a partial
   graft is not.

7. **`owner/repo` may not contain a `.` or `..` path segment.** `..` matches
   increment 1's `[A-Za-z0-9._-]+` character class, so `{"repo": "../.."}`
   expands to a URL whose path climbs above the configured base. Harmless against
   the default base (`https://github.com`, no path) and a real traversal against
   a GitHub Enterprise base with a path prefix. Refused in `cloneUrl()`, where the
   shape is already validated.

8. **`github-base-url` is configuration.** Default `https://github.com`; it is
   what GitHub Enterprise Server needs, and it is what makes resolution testable
   against an in-process fixture instead of the public internet. The derived URL
   still passes the same scheme and host allowlists, so the knob cannot widen
   what a manifest may reach beyond what an operator allowed. It remains the only
   place the `owner/repo` convention lives.

9. **The hardened connection factory is installed per fetch, never globally.**
   `HttpTransport.setConnectionFactory` is a JVM-wide static; setting it would
   silently change every other JGit HTTP transport in the process, including the
   marketplace upstream fetch this change deliberately leaves alone.
   `FetchCommand.setTransportConfigCallback` plus
   `TransportHttp.setHttpConnectionFactory` scopes it to the one fetch that needs
   it, which also means a future change to the upstream path is an explicit
   decision rather than a side effect already taken.
   *Consequence:* a non-HTTP transport (e.g. `file://`, reachable only where an
   operator has put `file` in `allowed-url-schemes`) bypasses the address policy
   because it has no addresses. `github` sources derive an `http`/`https` URL from
   `github-base-url`, so this is reachable only by an operator pointing that at a
   filesystem path, and it is stated in the configuration reference rather than
   silently true.

10. **Address policy has two tiers, and the top one is not configurable.**
    Always refused: link-local (`169.254.0.0/16`, `fe80::/10` — this is the cloud
    metadata endpoint), multicast, unspecified, broadcast, `0.0.0.0/8`,
    IPv4-mapped and IPv4-compatible IPv6 (an encoding of a v4 address that would
    otherwise dodge the v4 checks), and any address that is not a global unicast
    address. Refused unless `allow-private-networks: true`: loopback, RFC1918,
    CGNAT `100.64.0.0/10`, IPv6 unique-local `fc00::/7`, site-local.
    The split exists because loopback is a legitimate development and test
    topology while a metadata endpoint never is, and because a test fixture on
    `127.0.0.1` must not be able to make the metadata case pass by accident.
    Both tiers are checked against **every** address the hostname resolves to,
    not just the first.

11. **DNS is resolved once, and the connection is made to the address that was
    validated.** The factory resolves the host, validates every address, then
    opens the socket to a chosen validated address with the original hostname
    preserved for TLS SNI and certificate verification
    (`InetSocketAddress(InetAddress, port)` — no second resolution). A rebind
    between validation and connect therefore cannot reach a private target.

12. **Redirects are re-validated, not followed by the JDK.**
    `setInstanceFollowRedirects(false)`; the factory returns the redirect to
    JGit, which asks for the next URL through the same factory, so every hop gets
    the full check. Additionally refused: a redirect leaving the origin host, an
    `https` → `http` downgrade, a target whose scheme is not allowlisted, a
    target carrying userinfo, a port outside `{443}` (plus `80` when `http` is
    allowlisted), and more than `max-redirects` hops.

13. **Budgets are enforced where the bytes are, and again on the tree.** The
    factory's response stream is wrapped in a counting stream that throws when
    `max-received-bytes` is exceeded, so a pack bomb is refused mid-transfer
    rather than after inflation. After the fetch, the resolved commit's tree is
    walked once for `max-objects`, `max-blob-bytes`, `max-inflated-bytes`,
    `max-tree-depth`, and the inflation ratio (`inflated / received`). Per source
    and, for bytes and objects, accumulated across the closure. A wall-clock
    `deadline` bounds the whole resolution and is also handed to JGit as the
    transport timeout.
    *Why both layers:* the received-byte cap alone cannot see a small pack that
    inflates enormously; the tree walk alone cannot stop a stream that never ends.

14. **Failure is atomic by ordering, not by rollback.** Nothing outside the
    quarantine object database is touched until the composite commit exists and
    its own manifest has passed the local-only gate. On any failure the
    scaffolding refs are pruned — leaving the fetched objects unreachable — the
    snapshot is recorded at the **upstream** SHA in `rejected` with the violation,
    and no `refs/snapshots/<composite>` is ever written. Published content is not
    reachable from this path at all: only `ApprovalService` publishes, and a
    rejected snapshot is not approvable.

15. **Concurrency is unchanged.** Resolution happens inside
    `IngestionService.ingest`'s existing per-marketplace `ReentrantLock`, so two
    ingestions of the same marketplace serialise exactly as they do today, and the
    `DuplicateKeyException` fallback still covers a second gateway instance.
    Identical sources within one manifest are resolved once (dedupe on the
    canonical clone URL), which is also the visited set a future depth increase
    would need.

## Configuration

```yaml
skills-gateway:
  ingestion:
    external-sources:
      enabled: false
      allowed-types: [github]
      allowed-hosts: []
      max-sources: 20
      github-base-url: https://github.com   # GitHub Enterprise Server
      allow-private-networks: false         # loopback/RFC1918; never link-local
      budgets:
        max-received-bytes: 50MB            # per source, compressed, on the wire
        max-inflated-bytes: 200MB           # per source, after inflation
        max-closure-bytes: 500MB            # inflated, across every source
        max-inflation-ratio: 100
        max-objects: 20000                  # per source
        max-blob-bytes: 10MB
        max-tree-depth: 32
        max-redirects: 3
        deadline: 5m                        # whole resolution, wall clock
```

Every key is a nested record with defaults in the existing `Sync`/`Vetting`
style, so an absent block is the shipped default and an upgrade changes nothing.
`enabled: false` remains the default, and with it this change is inert.

## Failure model (Tier 3)

| Mode | How it hurts | The layer that catches it |
| --- | --- | --- |
| SSRF to cloud metadata | credential theft from the ingestion host | tier-1 address refusal, unconditional; asserted through a redirect too |
| SSRF to internal API / RFC1918 | lateral movement | tier-2 address refusal, default-off `allow-private-networks` |
| DNS rebinding | validation passes, connect goes private | resolve once, connect to the validated address |
| Redirect escape | policy applied only to the first hop | per-hop re-validation, host pinning, downgrade refusal, hop bound |
| URL-encoding evasion (IP literal, decimal/octal/hex v4, v4-mapped v6, userinfo) | allowlist bypass | address policy operates on resolved `InetAddress`es, never on host text; userinfo refused outright |
| Pack / decompression bomb | memory or disk exhaustion | received-byte cap on the stream, inflated-byte cap and ratio cap on the tree |
| Huge repository / history | disk exhaustion, slow ingestion | object count, blob size, tree depth, per-closure byte cap |
| Hang / slowloris | ingestion thread pinned forever | connect and read timeouts, wall-clock deadline |
| Partial fetch, mid-fetch failure, cancellation | half-resolved snapshot reaching `held` | nothing is pinned or recorded until the composite exists and passes the gate; refusal path records `rejected` at the upstream SHA |
| Graft-path injection (`../`, `_plugins` collision, duplicate names) | writing outside the intended subtree | strict name pattern, reserved-directory refusal, duplicate refusal |
| Path traversal in `owner/repo` | escaping a GHES base path prefix | `.`/`..` segment refusal in `cloneUrl()` |
| Silent pin loss (declared `sha` ignored) | serving a commit the manifest did not name | declared `ref`/`sha` refused, naming the field |
| Concurrency: two ingests of one marketplace | duplicate rows, ref lock races | existing per-marketplace lock, existing `DuplicateKeyException` fallback |
| Regression of the default | an unconfigured gateway starts fetching | `enabled: false` default; SVC_GW_0003 untouched and still green |
| Vetting bypass | external content served unvetted | the closure *is* the commit, so `VettingService` sees it; asserted with a planted secret |

Deliberately **not** covered, and stated as known limits in `evidence.md`: a
malicious TLS peer with a valid certificate for an allowlisted host; an operator
who allowlists a host they do not control; egress isolation itself (deployment
topology, ADR 0011 §3); a `file://` base URL, which no address policy can apply
to.

## Acceptance criteria (the named test list)

Pure-function suites (no Spring context, no database):

- `SourceAddressPolicyTests` (SVC_GW_0157)
  1. `169.254.169.254` refused with `allow-private-networks: true`
  2. `fe80::1` refused with `allow-private-networks: true`
  3. loopback `127.0.0.1` and `::1` refused by default, allowed when private
     networks are allowed
  4. RFC1918 `10.0.0.1`, `172.16.0.1`, `192.168.1.1` refused by default
  5. CGNAT `100.64.0.1` and unique-local `fc00::1` refused by default
  6. multicast, unspecified (`0.0.0.0`, `::`) and `0.0.0.0/8` always refused
  7. IPv4-mapped `::ffff:169.254.169.254` refused even with private networks
     allowed — the v4 rules are applied to the mapped address
  8. a hostname resolving to *both* a public and a private address is refused as
     a whole (every address is checked, not the first)
- `SourceUrlPolicyTests` (SVC_GW_0157)
  1. decimal (`http://2852039166/`), octal (`http://0300.0250.0.1/`) and hex
     (`http://0xA9FEA9FE/`) IPv4 encodings refused
  2. embedded credentials (`https://user:pw@host/x`) refused
  3. port outside the policy refused; `80` allowed only when `http` is
     allowlisted
  4. `https` → `http` redirect refused; redirect to another host refused;
     `max-redirects + 1` hops refused
  5. a redirect target whose scheme is not allowlisted refused
- `PluginSourceTests` additions (SVC_GW_0150 — existing suite)
  1. `{"repo": "../.."}` yields no clone URL
  2. `{"repo": "owner/./repo"}`-shaped inputs (a `.` segment) yield no clone URL
  3. a `github` source declaring `ref` or `sha` parses to the refusing variant
- `ManifestRewriterTests` (SVC_GW_0156)
  1. the rewritten manifest declares `./_plugins/<name>` for the external plugin
     and leaves every other field and every local source byte-identical
  2. the composite's parent is the upstream commit, and the upstream manifest
     blob is still reachable at the parent, byte-identical
  3. the same inputs twice ⇒ the same composite SHA; a different resolved plugin
     SHA ⇒ a different composite SHA; a bumped transformer version ⇒ a different
     composite SHA
  4. `_plugins` already present upstream ⇒ refused, no commit
  5. plugin name `../evil`, `_plugins`, `Upper` or empty ⇒ refused, no commit
  6. two external plugins with the same name ⇒ refused, no commit
  7. the composite manifest passes `ManifestPolicy.validate` (the GW_0152
     post-condition), and a rewriter that failed to rewrite one source is caught
     by it
- `ResolutionBudgetTests` (SVC_GW_0158)
  1. each of `max-received-bytes`, `max-inflated-bytes`, `max-closure-bytes`,
     `max-inflation-ratio`, `max-objects`, `max-blob-bytes`, `max-tree-depth`
     refuses at the boundary and passes just under it
  2. an expired deadline refuses before the next fetch starts

Container-backed suite, its own Spring context with `enabled: true`,
`allowed-types: [github]`, `github-base-url` pointed at an in-process JGit-backed
smart-HTTP fixture on loopback, `allow-private-networks: true`:

- `ExternalSourceResolutionTests` (SVC_GW_0155, SVC_GW_0156, SVC_GW_0161)
  1. a manifest with one `github` source ⇒ snapshot **held**, its SHA is the
     composite, its manifest declares only local sources, and
     `_plugins/<name>/skills/…` is present in the served tree
  2. the content inventory (`SnapshotContentService`) lists the external
     plugin's skills with no API change
  3. approving it and cloning through the facade yields a manifest containing no
     external URL — every source a client dereferences is inside the gateway
   4. a planted secret inside the *external* repository produces a blocking
      vetting finding on the composite (no vetting bypass)
  5. re-ingestion with nothing changed ⇒ the same snapshot row, no duplicate
  6. the external repository moves on ⇒ a new composite SHA, a new held
     snapshot, and the previously approved snapshot still served
  7. the external repository is unreachable ⇒ snapshot **rejected** at the
     upstream SHA, no composite ref, no `_plugins` anywhere, published tip
     untouched
  8. the external repository is served then killed mid-transfer ⇒ same as 7
  9. two concurrent ingestions of the same marketplace ⇒ one snapshot row, both
     callers see it
  10. a redirect chain leaving the fixture's host ⇒ rejected, and the fixture
      asserts the off-host target was never requested
  11. a redirect to `http://169.254.169.254/` ⇒ rejected, target never requested
  12. a source over the received-byte budget ⇒ rejected, no graft
  13. `_plugins` colliding upstream ⇒ rejected
  14. a source declaring `sha` ⇒ rejected, naming the field

Regression, unmodified:

- `IngestionTests.externalPluginSourceIsRejectedAndCannotBeApproved`
  (SVC_GW_0003) and the `HostedLifecycleTests` key-per-type case still pass under
  the default configuration, untouched.
- `ManifestPolicyTests`, `ExternalSourceAdmissionTests`, `PluginSourceTests`
  keep every existing assertion.

## Setup plan

- Isolation: a dedicated git worktree off `origin/main`, branch
  `feat/external-source-resolution`. The worktree shares the repository's
  `target/` state only through a fresh build, so the gates run there.
- New dependencies: **none**. The in-process smart-HTTP fixture is built from
  `com.sun.net.httpserver.HttpServer` (JDK) plus JGit's `UploadPack`, which is
  already a dependency and is what the facade itself uses. No Testcontainers
  container is added: PostgreSQL comes from the existing Arconia dev service, and
  a fake upstream forge is an in-process fixture, per CLAUDE.md.
- Files added, by path: `ExternalSourceResolver.java`, `ManifestRewriter.java`,
  `SourceAddressPolicy.java`, `SourceUrlPolicy.java`,
  `GuardedHttpConnectionFactory.java`, `ResolutionBudget.java` (all under
  `src/main/java/dev/skillsgateway/server/ingestion/`); the test classes named
  above plus `GitHttpFixture.java` under
  `src/test/java/dev/skillsgateway/server/ingestion/`.
- Commit cadence: a commit per coherent step, DCO signed, on the branch.

## Risks / Trade-offs

- **[The composite SHA is a synthesised commit, so an operator cannot look it up
  upstream]** → that is the point (ADR 0011 §2), and the upstream commit is its
  parent, so `git log` from the served SHA shows it immediately. The commit
  message names it explicitly.
- **[A `github`-only resolver plus a configurable base URL looks like it admits
  more than it does]** → `allowed-types` still lists only `github`, and the
  `owner/repo` shape means a manifest cannot name a host. The base URL is
  operator configuration, checked by the same allowlists.
- **[Budgets that are too tight refuse legitimate marketplaces]** → every budget
  is configuration with a documented default, and a breach is a rejected snapshot
  naming the budget, so the operator sees which number to raise.
- **[Resolution makes ingestion slower and network-dependent]** → bounded by the
  deadline, and serving is unaffected: the facade never contacts an upstream, so
  a failing resolution leaves the last approved snapshot served.
- **[The hardened transport does not cover the marketplace upstream fetch]** →
  stated, scoped, and the URL there is admin-supplied at registration rather than
  manifest-derived. Named as the follow-up it is.

## Migration Plan

None. No schema change, no data migration, no API change. `enabled: false`
remains the default, so an existing deployment behaves exactly as it does today,
which SVC_GW_0003 — unchanged — pins.

## Open Questions (for the owner)

1. **Policy version in the composite commit.** ADR 0011 names the admission
   policy as an input to the composite SHA. This change stamps only
   `Transformer-Version`, because a policy digest would change the served SHA —
   and so force re-vetting and re-approval — when an operator allowlists an
   unrelated host. Confirm the deviation, or say the ADR's stricter reading wins.
2. **Requirement id block.** GW_0155 – GW_0158 and GW_0161, which is not a
   contiguous block and deliberately so: GW_0159 and GW_0160 were claimed by
   [#251](https://github.com/skillsgateway/skillsgateway/pull/251) while this
   change was in flight, and GW_0155 – GW_0158 were still free on `main`. Filling
   the gap beats leaving four ids permanently unused. GW_0160 is the highest in
   `requirements.yml`; no in-flight change under `openspec/changes/` claims
   anything above GW_0149.
3. **Deferring the closure tables.** Provenance is in the composite commit
   (parent + message trailers) rather than in `closures`/`closure_nodes`. That is
   enough for a reviewer and an auditor, and not enough for the blast-radius
   query. Confirm that the tables land with the increment that wires
   `RevetService`, rather than here.
4. **`allow-private-networks`.** Introduced because loopback is the only way to
   test resolution in-process. Should it be refused outright in a production
   profile, or is default-off plus the documented hardening note enough?
