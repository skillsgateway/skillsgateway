# Evidence: external-source-resolution

This change opens the gateway's first outbound network path whose target is
chosen by content the gateway does not control, so the old-coder discipline
applies at **Tier 3**. The failure model is in `design.md`; this report records
what was executed, what it produced, and where the loop was not followed.

**Source state:** commit `069ba928d47001acb55c58b4cbefbd4af9ec6b42`, branch
`feat/external-source-resolution`, rebased onto `origin/main` at
`f3b9d92af4e7102a2d62045f1639a3655dabdc15` (#254). That is the implementation
commit — the last code and documentation edit — and **every number below comes
from one pass run against exactly that tree**. Two commits follow it: this
report, and the archive that moves the change into `openspec/specs/`. Neither
touches anything a gate reads except `openspec validate`, which is therefore
recorded both before the archive (31 items) and after it (30, one fewer because
the change is no longer an open change).

**Requirement ids.** GW_0155 — Resolution of admitted external plugin sources
into quarantine through GW_0158 — Resource-bounded source resolution, plus
GW_0161 — A failed resolution leaves the snapshot rejected and nothing
half-resolved. Deliberately not a contiguous block: GW_0159 — Approval-pending
lifecycle event and GW_0160 — The approval-pending event announces without
disclosing were claimed by
[#251](https://github.com/skillsgateway/skillsgateway/pull/251) while this change
was in flight, and GW_0154 — Fetch ledger records the advertised ref a want
resolves to by
[#249](https://github.com/skillsgateway/skillsgateway/pull/249). GW_0155 – GW_0158
were still free on `main`, so the gap is filled rather than left permanently
unused.

## Spec ↔ test mapping

| Requirement | What is proved | Test(s) |
| --- | --- | --- |
| **GW_0155 — Resolution of admitted external plugin sources into quarantine** | an admitted `github` source is fetched into the marketplace's quarantine and pinned; identical sources fetch once; the scaffolding refs are gone afterwards; a declared `ref`/`sha` is refused by name | `ExternalSourceResolutionTests` (SVC_GW_0155): `an_admitted_source_becomes_a_held_composite_whose_manifest_is_gateway_local`, `the_scaffolding_references_the_fetch_used_are_not_left_behind`, `a_source_pinned_to_a_commit_is_refused_rather_than_resolved_elsewhere`, `re_ingesting_unchanged_content_produces_the_same_snapshot`; `PluginSourceTests.a_source_pinned_to_a_ref_or_a_commit_is_refused_by_name` |
| **GW_0156 — Deterministic composite snapshot with a gateway-local manifest** | the rewritten manifest points only inside the commit; the upstream commit is the parent and its manifest is byte-exact; determinism, and the three inputs that change the SHA; every graft hazard refuses with no commit; the composite passes the local-only gate | `ManifestRewriterTests` (12 cases, SVC_GW_0156); `ExternalSourceResolutionTests`: composite/parent, content inventory, facade clone, planted secret, moved head, reserved-directory collision |
| **GW_0157 — Address, redirect and transport policy for source resolution** | both address tiers, IPv4-mapped/compatible unwrapping, mixed-resolution refusal, ambiguous literals, embedded credentials, and every redirect transition | `SourceAddressPolicyTests` (11 cases, SVC_GW_0157); `SourceUrlPolicyTests` (15 cases, SVC_GW_0157); `ExternalSourceResolutionTests`: off-host redirect (target never contacted), metadata-endpoint redirect, over-long chain |
| **GW_0158 — Resource-bounded source resolution** | each bound refuses at the boundary and passes just under it; the ratio floor; the closure accumulation; the deadline | `ResolutionBudgetTests` (12 cases, SVC_GW_0158); `ExternalSourceResolutionTests`: received-byte flood, over-budget file |
| **GW_0161 — A failed resolution leaves the snapshot rejected and nothing half-resolved** | unreachable source, mid-transfer death, and every policy refusal record the snapshot at the **upstream** SHA in `rejected`, unapprovable, with no graft; the previously approved snapshot is still served; concurrent ingestion yields one row | `ExternalSourceResolutionTests` (SVC_GW_0161): `an_unreachable_source_rejects_the_snapshot_at_the_upstream_commit_and_leaves_nothing_grafted`, `a_transfer_that_dies_part_way_through_rejects_the_snapshot`, `a_failed_resolution_leaves_the_previously_approved_snapshot_served`, `two_concurrent_ingestions_of_one_marketplace_produce_one_snapshot` |
| **GW_0152 — No snapshot is held while a plugin source is not gateway-local** (unchanged) | the gate still refuses an admitted-but-unresolved source, **and** the rewriter runs its own output back through that same gate | `ManifestPolicyTests` (4 cases, unchanged); `ManifestRewriterTests.the_composite_manifest_is_put_back_through_the_local_only_gate` |
| **GW_0137 — An ingestion reports a pinned snapshot only when it is pinned** (unchanged) | quarantine holds exactly `refs/snapshots/<composite sha>` for a resolved snapshot — the ref approval publishes from | `ExternalSourceResolutionTests.an_admitted_source_becomes_a_held_composite_whose_manifest_is_gateway_local` |
| **GW_0003 — Local-only plugin sources unless external sources are enabled** (rev. 0.2.0) | unchanged behaviour for an unconfigured gateway | `IngestionTests.externalPluginSourceIsRejectedAndCannotBeApproved`, `HostedLifecycleTests.a_pushed_manifest_declaring_a_non_local_source_is_rejected` — **both unmodified** |

## The adversarial cases, one by one

Everything below refuses. The happy path is one property; the rest of the suite
is the ways the resolver could reach something it must not, spend more than it
may, or leave a snapshot half-resolved.

### SSRF and allowlist evasion

| Case | Result |
| --- | --- |
| `169.254.169.254` (cloud metadata), as a direct address | refused, **and not unlockable** — refused with `allow-private-networks: true` as well |
| `fe80::1` (IPv6 link-local) | refused under every configuration |
| `::ffff:169.254.169.254` (IPv4-mapped) | refused — the v4 rules are applied to the address it encodes |
| `::169.254.169.254` (IPv4-compatible) | refused, same reduction |
| Loopback `127.0.0.1`, `::1` | refused by default; permitted only with `allow-private-networks: true` |
| RFC1918 `10.0.0.1`, `172.16.0.1`, `192.168.1.1` | refused by default |
| CGNAT `100.64.0.1`, unique-local `fc00::1`, `fd12:3456::1` | refused by default |
| Multicast, unspecified (`0.0.0.0`, `::`), `0.0.0.0/8`, broadcast, `240.0.0.0/4` | refused under every configuration |
| A hostname resolving to **both** a public and a private address | refused as a whole — every resolved address is checked, not the first |
| A hostname resolving to nothing | refused rather than passed through |
| Decimal IPv4 literal (`http://2852039166/`, `http://2130706433/`) | refused as an ambiguous address literal |
| Octal (`http://0300.0250.0.1/`, `http://0177.0.0.1/`) | refused |
| Hexadecimal (`http://0xA9FEA9FE/`, `http://0x7f.1/`) | refused |
| Short dotted (`http://127.1/`, `http://169.254.43518/`) | refused |
| Embedded credentials (`https://user:pw@host/x`, `https://user@host/x`) | refused |
| A plain dotted quad or bracketed IPv6 literal | *not* refused as ambiguous — left to the address policy, which is the component that should decide addresses |
| A manifest naming a host at all | **impossible for the shipped type**: `github` derives its URL from `github-base-url` and an `owner/repo` shape that cannot contain a host |
| `{"repo": "../.."}`, `"./x"`, `"x/."`, `"x/.."`, `".."` | no clone URL, so refused rather than expanded into a path that climbs above the base |
| Non-allowlisted schemes (`file:`, `ssh:`, `git:`, `gopher:`) | refused, reusing the allowlist registration enforces |

### Redirects

| Case | Result |
| --- | --- |
| A redirect leaving the origin host, served by the fixture | snapshot **rejected**, and the fixture asserts the off-host path was **never requested** |
| A redirect to `http://169.254.169.254/latest/meta-data/iam/security-credentials/` | rejected; refused from the `Location` header, before any hop |
| A redirect chain of ten against `max-redirects: 2` | rejected |
| `https` → `http` downgrade | refused |
| A redirect changing the port on the same host | refused |
| A redirect to a non-allowlisted scheme, or carrying credentials | refused |
| A redirect with no `Location` | refused rather than ignored |

The "never requested" assertion is not vacuous: the happy-path test asserts the
fixture **does** record the path it served, so an empty log means the request was
not made rather than that the log does not work.

### Budgets — each proved to refuse

| Bound | Refuses at | Passes at |
| --- | --- | --- |
| received bytes, per source | boundary + 1 (unit); a protocol-correct reference advertisement that never stops, cut off mid-transfer (integration) | boundary |
| uncompressed bytes, per source | boundary + 1 | boundary |
| uncompressed bytes, per manifest | two sources each inside their own bound, together over the closure bound | one source |
| inflation ratio (decompression bomb) | 100× above the floor | 10× at the ratio; anything below the floor |
| object count (repository size) | boundary + 1 | boundary |
| largest file | boundary + 1 (unit); a 128 KiB file against `max-blob-bytes: 64KB` (integration) | boundary |
| tree depth | boundary + 1 | boundary |
| wall-clock deadline (timeout) | one second past | at the deadline |

None of these exhausts the process: the received-byte bound is enforced on the
response stream as it is read, and everything else is measured from the objects a
completed fetch produced.

### Failure atomicity — GW_0161, and GW_0152 through every path

| Case | Result |
| --- | --- |
| Source unreachable (404) | snapshot `rejected` **at the upstream SHA**, `decidable() == false`, no `_plugins` path anywhere in the snapshot |
| Transfer dies part-way through | same |
| Over a budget | same |
| A refused address or redirect | same |
| A refused graft (reserved directory, bad name, duplicate name) | same, and no commit synthesised |
| A marketplace already serving an approved snapshot, whose next ingest fails | the rejected snapshot is recorded and a real `git clone` through the facade still receives the previously approved SHA |

No path reaches `held` with an unresolved source, which is GW_0152.

### Concurrency and repetition

| Case | Result |
| --- | --- |
| Two ingestions of the same marketplace at once, on two threads | one snapshot row; both callers receive the same id |
| Re-ingestion with nothing changed | the same snapshot row and SHA — the composite is deterministic, so the existing dedupe holds |
| The external repository moves on | a new composite SHA, a new **held** snapshot, and the previously approved snapshot still `approved` |

### Regression under the shipped default

`enabled: false` is the default and nothing in this change alters it. The two
suites that pin it were run **unmodified**:

- `IngestionTests.externalPluginSourceIsRejectedAndCannotBeApproved` (SVC_GW_0003)
- `HostedLifecycleTests.a_pushed_manifest_declaring_a_non_local_source_is_rejected`

`ManifestPolicyTests` and `ExternalSourceAdmissionTests` keep every existing
assertion; the only edits to them are the extra constructor argument for
`github-base-url`, with no assertion changed. `PluginSourceTests` keeps its
assertions and gains three cases.

### Vetting is not bypassed

A secret planted in the **external** repository produces a blocking `secret-scan`
finding on the composite, located at `_plugins/tools/DEPLOY.md:…`. No connector
changed: the closure *is* the commit, so the chain sees it.

## Gates — one pass, at `069ba92`

| Gate | Command | Result, verbatim |
| --- | --- | --- |
| Java + UI + jar | `./mvnw clean verify` | `BUILD SUCCESS`, `Total time:  04:56 min` |
| Java tests | (inside `verify`) | 93 classes, **490 tests, 0 failures, 0 errors, 0 skipped** |
| Formatting | `spotless:check` (inside `verify`) | `Spotless.Java is keeping 273 files clean - 0 needs changes to be clean, 273 were already clean, 0 were skipped because caching determined they were already clean` |
| Style | `checkstyle:check` (inside `verify`) | `You have 0 Checkstyle violations.` |
| Portal unit gate | `pnpm verify` (inside `verify`) | `Test Files  11 passed (11)`, `Tests  45 passed (45)` |
| Storybook story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files  3 passed (3)`, `Tests  6 passed (6)` |
| Real-browser e2e | `(cd src/main/frontend && E2E_GATEWAY_PORT=8137 pnpm e2e)` | `13 passed (50.1s)` |
| Requirements traceability | `reqstool status local -p docs/reqstool` | `154/154 complete · 0 incomplete · PASS` |
| OpenSpec, before the archive | `openspec validate --all --strict` | `Totals: 31 passed, 0 failed (31 items)` |
| OpenSpec, after the archive | `openspec validate --all --strict` | `Totals: 30 passed, 0 failed (30 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.40 seconds`, no strict failures |
| Mutation testing | `openspec/changes/external-source-resolution/mutants.sh` | `=== killed 15, survived 0 ===` |

The mutation runner moves into the archive with the change, so after the archive
commit its path is
`openspec/changes/archive/2026-09-03-external-source-resolution/mutants.sh`. It
resolves the repository root from its own location and aborts rather than
reporting a clean run if it ever lands somewhere that is not the root; it was
re-run from the archived path and reported the same 15 killed.

One deviation from the documented commands, and it is environmental rather than a
change to the gate: the e2e suite defaults to gateway port 8081, which an
unrelated process on this machine holds. `E2E_GATEWAY_PORT` is the script's own
override and CI uses the default; all 13 tests ran, nothing was skipped.

## Gauntlet layers

| Layer | Result |
| --- | --- |
| Full test suite | 490 Java + 45 portal unit + 6 storybook + 13 e2e, zero failures. No pre-existing failures to baseline against |
| Static types | `javac` and `tsc -b` inside `verify`, 0 errors |
| Lint + format | Spotless 273/273 clean, Checkstyle 0 violations, oxlint inside `pnpm verify` |
| Coverage on changed lines | **no coverage tool is configured in this project**, so this layer is not available as a gate. What stands in its place is mutation: 15 mutants across every new class, all killed, which is a stronger statement about the changed lines than execution counts would be |
| Mutation testing | 15/15 killed — see below |
| Property-based tests | **skipped** — no property-based framework in the project, and the added logic is a classifier over an enumerable input space rather than an algebra with invariants. The address, URL and budget suites enumerate that space directly |
| Complexity budget | every new method is single-purpose; the largest is `ExternalSourceResolver.fetch`, and the policy classes are pure functions returning a reason or `null` |
| Real execution | the facade clone test runs the **system git binary** against the running gateway and reads the served manifest off disk; the e2e suite runs the packaged jar against a real browser and a mock IdP |
| Supply chain & secrets | no new dependency (see the capability diff below); no credential of any kind in the tree — the fixture is an in-process server with no auth |
| Suite health | the affected suites were run repeatedly through the loop (five full `ExternalSourceResolutionTests` runs plus 30 mutant runs across two gauntlet passes) with no flake observed |

### Mutation testing

`mutants.sh` ships beside this report and is reproducible: it applies one
plausible bug at a time, runs the test that should catch it, requires a failure,
and restores the file in a trap. It fails closed in four directions — a wrong
working directory aborts, an inapplicable search string aborts (the mutant was
never applied, so a "kill" would be a lie), a surviving mutant exits non-zero,
and an interrupted run cannot leave a mutant in the tree.

**Its own negative control was observed rather than assumed.** The first run
reported **4 survivors**, which is the proof that the runner can report one; a
runner that only ever printed `killed` would have told us nothing. All four were
real holes in the suite, and each was closed:

| Survivor | Why it survived | Resolution |
| --- | --- | --- |
| *the GW_0152 post-condition over the rewritten manifest is dropped* | `ManifestRewriter` also compared the admitted-source names against the graft names, so every path that could reach a non-local composite manifest was refused earlier. The post-condition was unreachable — a safety net nothing could land in | Removed that half of the name comparison and made the rewriter leave an unresolved source **as declared**. The post-condition is now the single enforcement point, over the actual output instead of over a description of it. **Killed** |
| *plugin names may be paths or contain traversal* | the test grafted bad names into a manifest that did not declare them, so the *graft-is-declared* check answered first and the name pattern was never exercised | The test now builds a manifest declaring each bad name, so only the pattern can refuse, and asserts the pattern's own message. **Killed** |
| *redirect targets are not checked before the hop is taken* | the assertion was `contains("host")`, and the second-layer origin-pin message contains "host" inside "local**host**" | Tightened to `contains("redirect that leaves the host")`. **Killed** |
| *the pinned ref is the upstream commit rather than the served one* | nothing asserted that `refs/snapshots/<sha>` exists for the composite | Added an assertion that quarantine holds exactly `refs/snapshots/<composite sha>` — the GW_0137 pin approval publishes from. **Killed** |

Final run, on the rebased tree: **15 mutants, 15 killed, 0 survivors.** No
survivor is left unexplained, and none was classified as equivalent.

### What the tests found before mutation did

Two real defects, both fixed, both now regression-covered:

1. **Unsanitised remote text in a violation.** A JGit transport failure quotes
   the response it could not parse, so a hostile or broken upstream could put
   arbitrary bytes — including a NUL — into the snapshot's `violation`. PostgreSQL
   refuses the insert, which turns a *rejected snapshot* into a *failed
   ingestion*, and short of that the bytes would reach a portal page and the audit
   ledger. Found by the received-byte flood case failing with
   `ERROR: invalid byte sequence for encoding "UTF8": 0x00`. Messages are now
   sanitised and truncated.
2. **The inflation ratio refused ordinary content.** A 128 KiB file of repeated
   text arrives in a couple of hundred bytes — a ratio in the hundreds and a
   threat to nothing, because the absolute inflated bound already caps it. Found
   by the over-blob-budget case being refused for the wrong reason. The ratio is
   now judged only above a floor derived from the per-source inflated bound. This
   is a **visible spec revision**, and `ResolutionBudgetTests` pins both sides of
   the floor.

### Capability diff, stated plainly

This change starts using the network from a path that did not use it. That is the
whole point of the increment and the reason GW_0157 and GW_0158 exist. Nothing
else changed: no new dependency, no new subprocess (JGit only — never the git
binary in production code), no new filesystem or environment access, no schema
change, no API change, no portal change. The test fixture is built from the JDK's
own `com.sun.net.httpserver` and JGit's `UploadPack` — the same `upload-pack` the
facade serves — so no container and no dependency was added for it either.

## Where the old-coder loop was not followed, honestly

- **Spec approval: not obtained (autonomous run).** `design.md` is the executable
  specification and it was committed before the implementation, but no human
  approved it before code was written. It is therefore the artifact to review
  after the fact, and this report claims correspondingly lower confidence.
- **RED was observed for four of the six new classes, not all six.**
  `SourceAddressPolicyTests` (11/11), `SourceUrlPolicyTests` (15/15),
  `ResolutionBudgetTests` (11/11 at the time) and `ManifestRewriterTests` (12/12)
  were each run against a stub throwing `UnsupportedOperationException` and
  watched to fail on behaviour before any implementation existed.
  `ExternalSourceResolver`, `GuardedHttpConnectionFactory` and the
  `IngestionService` wiring were implemented before
  `ExternalSourceResolutionTests` existed — the loop was not run there. The
  compensating layer is mutation: five of the fifteen mutants target exactly that
  code (scaffolding pruning, the redirect check, the pinned ref, and two budget
  paths), and all five are killed.
- **Three `PluginSourceTests` cases passed on first run** (the `.`/`..` shorthand
  refusal, the configured base URL, the declared-pin refusal), because they were
  written alongside the change to `PluginSource`. They are not vacuous: the
  assertions name specific outputs rather than merely non-null, and the mutation
  run covers the shorthand and pin behaviour through the rewriter and resolver.
- **Independent verification: not performed.** A declared downgrade, per the
  skill: the adversarial pass in this report is the author attacking their own
  work and shares its blind spots.
- **Changed-line coverage was not measured**, because the project configures no
  coverage tool. Recorded as unavailable rather than as passed.

## Known limits, deliberately not covered

- **Connect-time address pinning.** Every resolved address is validated, and
  re-validated per request, but the socket is opened against the hostname.
  `HttpURLConnection` offers no way to pin an address without overriding a
  restricted request header process-wide. Bounded today because a `github`
  source's host is operator configuration and an `owner/repo` shorthand cannot
  carry a host, so no manifest chooses what is resolved. Recorded as a deviation
  in ADR 0011 and in `concepts/trust-boundaries.md`; it lands with the increment
  that admits manifest-supplied URLs.
- **The marketplace upstream fetch is not behind this policy.** Admin-supplied at
  registration and already scheme-checked — a different trust level from a
  manifest-derived URL. Named as a follow-up rather than folded in.
- **HTTPS end-to-end.** The integration fixture serves `http` on loopback. The
  scheme, downgrade and port rules are unit-tested; a TLS-serving fixture is not
  in this change.
- **Egress isolation itself** is deployment topology, not something the gateway
  can enforce (ADR 0011 §3). A malicious peer holding a valid certificate for an
  allowlisted host, and an operator who allowlists a host they do not control,
  are both outside what any of this can see.
- **A `file://` `github-base-url`** would bypass the address policy, which has no
  addresses to check. Reachable only by an operator who both puts `file` in
  `allowed-url-schemes` and points the base URL at a path. Stated in the
  configuration reference.

## Open questions for the owner

Restated from `design.md` because they are decisions, not details:

1. **The policy version is not an input to the composite SHA**, although ADR 0011
   says it should be. Only the transformer version is. Hashing the admission
   configuration would make the served SHA change — forcing re-vetting and
   re-approval — when an operator allowlists an unrelated host.
2. **`snapshots.upstream_sha` was not added.** The upstream commit is the
   composite's parent and is named in its commit message; nothing reads an
   upstream SHA today. The column earns its keep with the closure tables.
3. **The closure tables are deferred** to the increment that wires the
   blast-radius query into `RevetService`.
4. **`allow-private-networks`** exists because loopback is the only way to test
   resolution in-process. Default off plus the documented hardening note, or
   should a production profile refuse it outright?
