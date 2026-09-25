# SPEC — upstream-credentials (#494)

- Tier: **3**. A secret is added to the registration trust boundary, and it
  travels to a server the gateway does not control.
- Spec approval: **not obtained (autonomous run)**. The proposals in #494 were
  settled by the owner's delegate. This SPEC is the artifact to review after
  the fact.
- Setup plan:
  - Tools to install: none.
  - Isolation: branch `feat/upstream-credentials`, stacked on
    `fix/ingest-failures-say-why` (#497), in the main checkout. Not a worktree:
    the gauntlet needs the built `node_modules` and Docker.
  - Git: signed-off commits. This SPEC and the OpenSpec change are committed
    first, then the tests shown failing, then the implementation.
    `evidence.md` comes last, before the archive commit.
  - Files the gauntlet adds, by path:
    `openspec/changes/upstream-credentials/mutants.sh`, the manual mutation
    runner. It archives with the change.
  - New dependencies: none.
  - Tokens in tests are made-up strings, such as `tok-acme-...`. No test
    reaches the internet: every upstream is an in-process `GitHttpFixture` on
    loopback.

## Failure model

| # | How this change could hurt | What catches it |
|---|---|---|
| F1 | A token is sent to a server outside its prefix via a redirect to another port, another path or another scheme | S4, S5, U4 |
| F2 | A redirect into another credentialed prefix switches to that prefix's token | S6 |
| F3 | A prefix matches by string, not by segment (`acme` matches `acme-evil`) | U1 |
| F4 | A non-canonical path (`/acme/../evil`, `%2e%2e`, `%2f`) reads as inside the prefix | U3 |
| F5 | The shorter prefix wins over the longer one | U2, S1 |
| F6 | The token leaks into a response, the row, the ledger, `fetch_log`, a log line, or `toString` | S7, S8, U6 |
| F7 | An unset environment variable sends the literal `${VAR}` as the password | U5 |
| F8 | A cleartext token goes to a non-loopback host | U5 |
| F9 | Registration and ingestion diverge, so one is credentialed and the other is not | S1 (both paths, one fixture that demands the credential) |
| F10 | An anonymous upstream changes behaviour, or gets a header it never had | S2; the existing `UpstreamReachabilityTests` and `IngestFailureTests` stay green |
| F11 | A URL with userinfo is still accepted and stores a credential | S9 |
| F12 | A wrong token is reported as something other than not-found-or-auth | S3 |

## Scenarios

```gherkin
Feature: credentials for private upstreams, matched by URL prefix

  # --- unit (UpstreamCredentialsTests, no Spring) ---

  Scenario: U1 matching is by whole path segment
    Given a credential for "https://forge.example/acme"
    Then  "https://forge.example/acme/skills.git" and "https://FORGE.example:443/acme/x" match
    And   "https://forge.example/acme-evil/x.git", "http://forge.example/acme/x",
          "https://forge.example:8443/acme/x" and "https://forge.example/" do not

  Scenario: U2 the longest prefix wins
    Given credentials for "https://forge.example/" and "https://forge.example/acme/"
    Then  "https://forge.example/acme/x.git" selects the second, and "https://forge.example/other/x.git" the first

  Scenario: U3 non-canonical request URLs get no credential
    Given the credential selected for "https://forge.example/acme/x.git"
    Then  requests to ".../acme/../evil/x.git", ".../acme/%2e%2e/evil", ".../acme%2fevil",
          ".../acme/x%5c..", and "https://u@forge.example/acme/x.git" carry no Authorization

  Scenario: U4 the connection factory attaches the header only under the prefix
    Given the factory for the credential selected by "https://forge.example/acme/x.git"
    Then  a connection to ".../acme/x.git/info/refs?service=git-upload-pack" carries "Basic base64(user:token)"
    And   one to "https://forge.example/other/x.git/info/refs" carries none
    And   an Authorization set by JGit on an out-of-prefix connection is dropped
    And   the JDK is never allowed to follow redirects

  Scenario: U5 an unusable entry stops startup, naming the entry but not the token
    Then  each of these is refused with a message naming "upstream-credentials[<i>]" and the prefix,
          and never the token:
          token "${SGW_MISSING}"; blank token; blank username; "http://forge.example/";
          "https://u:p@forge.example/"; "https://forge.example/?q"; "https://forge.example/#f";
          two entries for "https://forge.example/acme" and "https://FORGE.example/acme/"; "ssh://forge.example/"
    And   "http://127.0.0.1:<port>/", "http://localhost/" and "https://forge.example/acme" load

  Scenario: U6 the properties never print the token
    Then  toString of the credential, the ingestion block and the root properties omits the token

  # --- integration (UpstreamCredentialsTests over Spring + GitHttpFixture) ---

  Scenario: S1 a private upstream registers and ingests with its prefix's credential
    Given a forge that answers 401 for /private/ unless the Basic credential is "sgw:<acme token>"
    And   credentials for "http://127.0.0.1:<port>/" (another token) and "http://127.0.0.1:<port>/private/" (the acme token)
    When  an administrator registers "http://127.0.0.1:<port>/private/skills.git" and ingests it
    Then  registration answers 201 and the ingest creates a snapshot
    And   every request the forge saw under /private/ carried the acme credential

  Scenario: S2 an upstream under no prefix is read anonymously
    Given the forge on a second port with no credential configured for it
    When  it is registered
    Then  no request carried an Authorization header

  Scenario: S3 a refused token is reported as not-found-or-auth
    Given the forge demands a different token under /locked/, and the acme prefix covers /locked/ too
    When  "…/locked/skills.git" is registered
    Then  the answer is 502 with the reason "repository not found or requires authentication"

  Scenario: S4 a redirect to the same host on another port carries no credential
    Given the credentialed forge redirects every request to a second forge on another port
    When  the credentialed URL is registered
    Then  the second forge saw requests and none carried an Authorization header

  Scenario: S5 a redirect to another path on the same host carries no credential
    Given the credentialed forge redirects /private/skills.git to /public/skills.git
    When  the credentialed URL is registered
    Then  no request outside /private/ carried an Authorization header

  Scenario: S6 a redirect into another credentialed prefix does not switch tokens
    Given a second credential for "http://127.0.0.1:<port>/other/", and a redirect from /private/ to /other/
    When  the /private/ URL is registered
    Then  no request under /other/ carried either token

  Scenario: S7 the token is never repeated
    Given a forge that refuses the token under /locked/
    When  the /locked/ URL is registered, and a credentialed marketplace whose upstream then refuses it is ingested
    Then  neither token appears in any response body, the marketplace read, the ledger,
          fetch_log, or the captured log output

  Scenario: S8 the token is scrubbed from a failure a server quotes
    Given an UpstreamFailure whose root cause contains a configured token
    When  it is scrubbed
    Then  the token is replaced in the reason, the next step and the root cause

  Scenario: S9 a clone URL with userinfo is refused
    When  "http://sgw:secret@127.0.0.1:<port>/acme/skills.git" or "http://sgw@…" is registered
    Then  the answer is 400, the forge saw no request, and nothing is created

  Scenario: S10 the ledger names the prefix, never the token
    When  the credentialed upstream and an anonymous one are registered
    Then  the credentialed "marketplace-registered" detail contains "credential=http://127.0.0.1:<port>/private/"
    And   the anonymous one contains no "credential="
```

## Invariants that must survive

- An upstream under no prefix is read exactly as #497 reads it: no factory is
  installed, and the existing `UpstreamReachabilityTests`, `IngestFailureTests`
  and `EstateReconciliationTests` stay green unchanged.
- The address policy is still not applied to marketplace upstreams.
- No schema change, no API contract change, and no new role.
- `ConfigSurfaceBudgetTests` grows by exactly one leaf.

## SVC mapping

| SVC | Scenarios |
|---|---|
| SVC_GW_INGEST_0050 — A private upstream registers and ingests with its prefix's credential | S1, S2, U1, U2 |
| SVC_GW_INGEST_0051 — A redirect out of the prefix carries no credential | S4, S5, S6, U3, U4 |
| SVC_GW_INGEST_0052 — The token appears in no response, record, ledger entry or log | S3, S7, S8, U6 |
| SVC_GW_INGEST_0053 — An unusable credential entry stops startup and names the entry, not the token | U5 |
| SVC_GW_INGEST_0054 — A clone URL with userinfo is refused and nothing is created | S9 |
| SVC_GW_INGEST_0055 — The registration ledger entry names the credential prefix | S10 |

## Revisions during implementation

- **S1.** The forge saw one request that was not a git request: the
  forge-metadata lookup registration makes (`ForgeMetadataService`, a REST
  call to `/api/v1/repos/<owner>/<repo>`). The credential does not ride on it.
  It rides only on the git transport, which is what #494 scopes. S1 now
  asserts that every git request under `/private/` carries the credential,
  and that the metadata lookup carries none. For a private repository, the
  metadata is therefore absent. That is a known limit, and it is stated in the
  docs.
- **U5 (loopback).** The test counted three tokens for four entries. That was
  an arithmetic slip in the test, not a behaviour change.
