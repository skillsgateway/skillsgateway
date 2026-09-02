# Evidence: external-plugin-sources

Trust boundary: ingestion and the registration allowlist's blast radius, so the
old-coder discipline applies. This report records what was executed and what was
not, including where the loop was not followed.

## Spec ↔ test mapping

| Requirement | What is proved | Test(s) |
| --- | --- | --- |
| GW_0150 — typed source model | every form classifies to its own variant; nothing falls through | `PluginSourceTests` (5 cases, SVC_GW_0150) |
| GW_0151 — config-gated admission | the shipped default admits nothing; each configured bound refuses; `npm`/`archive` unreachable by configuration | `ExternalSourceAdmissionTests` (10 cases, SVC_GW_0151); `ManifestPolicyTests.the_max_sources_bound_is_reached_through_a_whole_manifest` |
| GW_0152 — held only if gateway-local | the gate returns `null` — what `IngestionService` maps to held — only when every source resolves inside the served snapshot | `ManifestPolicyTests` (4 cases, SVC_GW_0152) |
| GW_0003 (rev. 0.2.0) — local-only by default | unchanged behaviour for an unconfigured gateway | `IngestionTests.externalPluginSourceIsRejectedAndCannotBeApproved`, `HostedLifecycleTests.a_pushed_manifest_declaring_a_non_local_source_is_rejected` — **both unmodified** |

## The adversarial cases (the guardrails, not the happy path)

- **The default admits nothing.** `the_shipped_default_admits_nothing` asserts
  the refusal still carries GW_0003's "non-local" phrase, which is also what the
  untouched `IngestionTests` assertion depends on.
- **A near-miss host never satisfies the allowlist.** `evil-github.com` and
  `github.com.evil.example` are both refused against an allowlist of
  `github.com` — exact matching, never suffix or contains.
- **A `github` shorthand cannot smuggle anything into its expansion.**
  `acme/tools/extra`, `acme/../../evil`, `evil.example/acme/tools`,
  `acme/tools?x=1` and `acme tools` all fail to expand and are refused rather
  than guessed at.
- **`npm` and `archive` are refused by a configuration that names them** in
  `allowed-types` — they are decided above the `enabled` branch.
- **Non-allowlisted schemes** (`file:`, `ssh:`, `git:`) refused, reusing the same
  allowlist registration enforces.
- **The bound is on external sources, and is reachable through a whole
  manifest**: three `github` sources under `max-sources: 2` refuse, and the
  refusal wins over the softer admitted-but-unresolvable violation recorded on
  the first source.
- **The gate never returns `null` for an unresolved external source.** That is
  GW_0152, and it is the case that keeps T4 closed while the reversal is staged.
- **Nothing in any of it touches a network.** Admission is a function of the
  manifest bytes and the configuration; there is no transport, client or URL
  connection anywhere in the added code.

## Where the old-coder loop was not followed, honestly

`PluginSourceTests`, `ExternalSourceAdmissionTests` and `ManifestPolicyTests`
were written **after** the code they cover, not proved red first. They are pure
functions over an explicit input space, so the risk that they pass vacuously is
low, but the loop was not run and this report should not imply it was.

One red-first signal did occur, and it was worth having: the **unmodified**
`IngestionTests` and `HostedLifecycleTests` were run against the new wiring and
failed with 5 errors — `ManifestPolicy` had gained a second constructor, so
Spring had no bean to build and the context failed to start. Adding `@Autowired`
to the properties constructor fixed it, and both suites then passed unmodified.
That is the defect a pure-unit-only change set would have shipped.

## Gates

Run against the tree at the implementation commit.

| Gate | Command | Result |
| --- | --- | --- |
| OpenSpec | `openspec validate --all --strict` | 31 passed, 0 failed |
| Docs | `mkdocs build --strict` | Documentation built, no strict failures |
| Formatting | `./mvnw spotless:check` | BUILD SUCCESS |
| Style | `./mvnw checkstyle:check` | BUILD SUCCESS |
| New unit tests | `./mvnw -Dtest=PluginSourceTests,ExternalSourceAdmissionTests,ManifestPolicyTests test` | Tests run: 19, Failures: 0, Errors: 0 |
| SVC_GW_0003 regression | `./mvnw -Dtest=IngestionTests,HostedLifecycleTests test` | Tests run: 8, Failures: 0, Errors: 0 — **both suites unmodified** |
| Full build | `./mvnw -o clean verify` | **Incomplete locally — see below** |
| Requirements | `reqstool status local -p docs/reqstool` | **Not run — see below** |

## What did not complete locally, and why

`./mvnw -o clean verify` was started and did **not finish** within the session
that wrote this report — it was still working through the Spring integration
suites, against a load average around 10 from unrelated concurrent work on the
same machine. At the point this report was written it had completed **77 test
classes, 388 tests, with one failure**:

- **`SbomTests.sbomEndpointServesCycloneDxBom`** — `/actuator/sbom` lists no
  `application` id because `target/classes/META-INF/sbom/` was never written.
  The `cyclonedx-maven-plugin` binds to `generate-resources`, and the run was
  made with `-o` (offline), so the aggregate BOM was not produced. This is an
  **artifact of the offline flag, not of this change** — nothing here touches
  the SBOM, the actuator, or the build's resource generation. It needs
  confirming with an online `./mvnw clean verify` before the PR leaves draft.

Everything else was green, including every suite that could plausibly be
affected: `IngestionTests` (5), `HostedLifecycleTests` (3), `VettingTests` (9),
`FacadeTests`, and the three new suites (19). Eight classes had not been
reached — `AdminAuditTests`, `AdminTests`, `AdoptionTests`, `ApprovalTests`,
`AuditExportTests`, `AuthTests`, `CatalogTests`, `ClaimMappedRoleIsEnforcedTests`
— none of which this change touches, though `ApprovalTests` is the one a reviewer
should want to see green given the trust boundary.

**`reqstool status local -p docs/reqstool` was not run**, because it consumes the
surefire results and the merged annotation file that a completed `verify`
produces. It cannot be trusted from a partial run — an earlier attempt against a
tree with no build artifacts reported `0/145 complete`, i.e. every pre-existing
requirement "not implemented" too.

## Not run

- `(cd src/main/frontend && pnpm test:stories)` and `pnpm e2e` — **not run**.
  This change touches no portal code, no API surface and no generated types, so
  neither suite can be affected by it; they run in CI regardless.
- `./mvnw -Pnative` — not run; unaffected and CI-owned.

## What must happen before this leaves draft

1. `./mvnw clean verify` **online**, to completion — confirming in particular
   that `SbomTests` passes once the aggregate BOM is generated.
2. `reqstool status local -p docs/reqstool` ending `PASS`, with GW_0150–GW_0152
   showing implemented and verified from the annotations added here.
3. `/opsx:archive` of this change as the final commit.

## Source state

`feat/external-plugin-sources`, implementation commit `38041fe`.
