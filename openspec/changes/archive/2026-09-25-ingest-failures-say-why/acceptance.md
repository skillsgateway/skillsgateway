# SPEC — ingest-failures-say-why (#489)

- Tier: **3**. Registration is a trust boundary (the URL allowlist and the
  gateway-pinned ref), and this change makes it contact the upstream.
- Spec approval: **not obtained (autonomous run)**. The proposals in #489 were
  settled by the owner's delegate. This SPEC is the artifact to review after
  the fact.
- Setup plan:
  - Tools to install: none.
  - Isolation: branch `fix/ingest-failures-say-why` in the main checkout. Not a
    worktree: the gauntlet needs the built `node_modules` and Docker, which a
    fresh worktree lacks.
  - Git: signed-off commits on the branch. This SPEC and the OpenSpec change are
    committed first, then one commit per GREEN checkpoint. `evidence.md` comes
    last, before the archive commit.
  - Files the gauntlet adds, by path:
    `openspec/changes/ingest-failures-say-why/mutants.sh`, the manual mutation
    runner. It archives with the change.
  - New dependencies: none.

## Failure model

| # | How this change could hurt | What catches it |
|---|---|---|
| F1 | A refused registration still leaves something behind: a row, a ledger entry, storage | S1–S4 assert the listing and the ledger |
| F2 | The probe opens a network path ingestion does not have (a second transport, no timeout) | One `UpstreamGit` owns both calls (design); S5 uses the same fixture both ways |
| F3 | The cause is still dropped, or the fallback hides the first error | S6, U3 |
| F4 | A private repository and a typo still read as a misleading "Authentication is required" | S2, S1, U1 |
| F5 | A failure message leaks a credential embedded in the URL into the response, the row or the ledger | S9 |
| F6 | A failed ingest records nothing, or a success keeps the old failure | S6, S7 |
| F7 | A failure on the automated paths (scheduler, webhook, push) is not recorded | S8 |
| F8 | An estate-declared marketplace with a dead upstream blocks startup or is dropped | S10 |
| F9 | Registration refuses a good upstream (over-refusal) | S5, and every existing registration test now uses a reachable fixture |
| F10 | The contract becomes breaking | `openapi.json` diff shows additions only; the contract gate in `verify` |

## Scenarios

```gherkin
Feature: ingest and registration failures say why

  Scenario: S1 registering a repository that does not exist is refused
    Given an in-process git HTTP server with no repository at /acme/missing.git
    When  an administrator registers "http://127.0.0.1:<port>/acme/missing.git"
    Then  the answer is 502, its detail reads "repository not found or requires authentication"
    And   the problem carries reason, rootCause and nextStep
    And   the marketplace is not listed, and no "marketplace-registered" entry names it

  Scenario: S2 registering a repository that answers 401 is refused the same way
    Given the server answers 401 with a Basic challenge for /private/
    When  an administrator registers "http://127.0.0.1:<port>/private/skills.git"
    Then  the answer is 502 with the reason "repository not found or requires authentication"
    And   nothing is created

  Scenario: S3 registering an upstream that cannot be reached is refused
    When  an administrator registers "http://127.0.0.1:<closed port>/x.git"
    Then  the answer is 502 with the reason "the upstream could not be reached"
    And   nothing is created

  Scenario: S4 registering a repository with no default branch is refused
    Given an empty repository on disk (no commits)
    When  an administrator registers its file:// URL
    Then  the answer is 502 with the reason "the upstream has no default branch"
    And   nothing is created

  Scenario: S5 registering a reachable upstream still succeeds, over HTTP and file://
    Given a repository published on the in-process server, and one on disk
    When  an administrator registers each
    Then  each answers 201 and is listed

  Scenario: S6 a failed ingest says why, and is recorded
    Given a registered marketplace whose upstream is then made unreadable (401)
    When  an approver ingests it
    Then  the answer is 502; its detail and rootCause name the cause, and nextStep is present
    And   the marketplace read shows lastIngestOutcome "failed", lastIngestAt set, lastIngestReason naming the cause
    And   the ledger has an "ingest-failed" entry for the marketplace, by the approver, whose detail names the cause
    And   a WARN line naming the marketplace and the cause is logged
    And   the thrown failure keeps the HEAD fetch error attached to the fallback's error

  Scenario: S7 a successful ingest clears the recorded failure
    Given the marketplace from S6, whose upstream is made readable again
    When  it is ingested
    Then  lastIngestOutcome is "succeeded", lastIngestReason is null, lastIngestAt moved forward

  Scenario: S8 an automated ingest failure is recorded the same way
    Given a scheduled marketplace whose upstream has been deleted
    When  the sync sweep runs
    Then  lastIngestOutcome is "failed", and the ledger's "ingest-failed" entry is by "scheduler"

  Scenario: S9 a credential in the URL is never repeated
    When  an administrator registers "http://user:s3cret@127.0.0.1:<port>/acme/missing.git"
    Then  the answer is 502 and neither the body nor any ledger entry contains "s3cret"

  Scenario: S10 a declared marketplace with an unreachable upstream is registered and reported
    Given an estate declaring marketplace A with an unreachable upstream, and marketplace B with a reachable one
    When  the estate is reconciled
    Then  A is created, its entry's detail names the unreachable upstream and the cause
    And   the ledger has a "marketplace-upstream-unreachable" entry for A by "config-reconciler"
    And   B is created as before, and the run does not throw

  Scenario: S11 the portal shows the last failed ingest
    Given a marketplace whose lastIngestOutcome is "failed" with a reason
    When  its page is opened
    Then  the header states that the last ingest failed, when, and the reason
    And   a marketplace whose last ingest succeeded shows no such alert
```

Unit scenarios for the translator, no Spring context:

- **U1:** a JGit `NoRemoteRepositoryException`, or a transport error carrying
  JGit's "not authorized", "Authentication is required…" or "not permitted"
  text, anywhere in the chain, maps to "repository not found or requires
  authentication".
- **U2:** `UnknownHostException` maps to "the upstream host could not be
  resolved"; `ConnectException` or `SocketTimeoutException` maps to "the
  upstream could not be reached"; an `SSLException` maps to "the TLS connection
  to the upstream failed"; anything else maps to "the upstream fetch failed".
- **U3:** a suppressed error is consulted. A generic primary with a suppressed
  401 maps to "not found or requires authentication", and the root cause is
  taken from that suppressed error.
- **U4:** userinfo in any URL in the root cause is redacted.

## Must NOT

- No existing SVC test is weakened or deleted. Tests that registered
  unreachable placeholder URLs (`https://example.com/x.git`, `github.com/…`)
  and expected 201 are moved to reachable local fixtures. Their assertions stay
  unchanged.
- Registration adds no endpoint, no configuration leaf and no Spring test
  context. There is no "test connection" endpoint (#489).
- A hosted marketplace's registration does not contact anything.
- Registration validation that refuses without the network (name, scheme,
  conflict, ref) still refuses without contacting the upstream. The probe runs
  last.
- The API contract stays additive: new nullable fields on the marketplace read,
  and a documented `502` on registration.
- Tests never contact the real internet.

## Gauntlet plan

- **Full suite, types, lint:** `./mvnw clean verify`, `pnpm test:stories`,
  `pnpm e2e`, reqstool, `openspec validate --all --strict`,
  `mkdocs build --strict`.
- **RED:** S1–S4, S6–S11 and U1–U4 run against the unfixed code and fail. S5 is
  regression armor, proven non-vacuous by mutant M3.
- **Manual mutants** (`mutants.sh`), each of which must be killed:
  1. the registration probe removed;
  2. the translator's not-found-or-auth branch removed;
  3. the probe always throws (over-refusal);
  4. the ingest failure not recorded on the marketplace;
  5. the success record not written;
  6. the suppressed first error not attached;
  7. the redaction removed;
  8. the estate path refuses instead of reporting.
- **Adversarial pass:** userinfo in the URL; a redirecting upstream; a 500 from
  the upstream; a concurrent registration of the same name while the probe
  runs (the unique index still decides).
- **Coverage:** skipped. There is no coverage tool in the build, so mutation
  stands in for it. EVIDENCE records this.
- **Independent verification:** not performed.

## Revisions

- 2026-09-25, during GAUNTLET: two mutants were added to the plan.
  - M9 moves the probe ahead of the name-conflict check, which proves the
    ordering test (regression armor, passing before the fix) can fail.
  - M10 removes the portal alert.
- M7 (redaction removed) is killed by the translator's unit test U4. S9 checks
  the same property end to end.
- Existing tests that registered placeholder URLs moved to local fixtures, with
  their assertions unchanged: `ClaimRoleMappingTests`, `ContentTests`,
  `DevAuthTests`, `DuplicateUrlWarningTests`, `HostedMarketplaceTests` and
  three arrangements in `EstateReconciliationTests`.
  - To support them, `GitHttpFixture` now serves a repository with or without a
    `.git` suffix or trailing slash, and can answer a JSON path (a forge's REST
    API beside its git service).
