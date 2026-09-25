# SPEC — upstream-github-app (#494, the parked GitHub App option)

- Tier: **3**. A signing key is added to the registration trust boundary; the
  gateway mints credentials from it and sends them to two servers it does not
  control (the GitHub API and the upstream).
- Spec approval: **not obtained (autonomous run)**. The design was settled by
  the owner's delegate. This SPEC is the artifact to review after the fact.
- Setup plan:
  - Tools to install: none.
  - Isolation: branch `feat/upstream-github-app` in the main checkout. Not a
    worktree: the gauntlet needs the built `node_modules` and Docker.
  - Git: signed-off commits. The OpenSpec change and this SPEC first, then the
    tests shown failing against a stub, then the implementation. `evidence.md`
    last, before the archive commit.
  - Files the gauntlet adds: `openspec/changes/upstream-github-app/mutants.sh`,
    the manual mutation runner. It archives with the change.
  - New dependencies: none. nimbus-jose-jwt (JWT signing) is already a compile
    dependency through `spring-boot-starter-oauth2-client`.
  - Keys in tests are generated at test time (`KeyPairGenerator`, 2048-bit RSA).
    No key is committed. Tokens are made-up strings (`ghs_test_...`). No test
    reaches the internet: the upstream and the GitHub API are in-process
    `GitHttpFixture`s on loopback.

## Failure model

| # | How this change could hurt | What catches it |
|---|---|---|
| G1 | The installation token is sent outside its prefix, or across a redirect | S6, S7 |
| G2 | The JWT is sent to the upstream, or follows an API redirect | S7, S8 |
| G3 | The token can read more than the one repository, or write | S2 |
| G4 | The marketplace URL steers the API host or path | S9, U7 |
| G5 | The key, JWT or token appears in a response, row, ledger, log or `toString` | S10, U6 |
| G6 | A cached token is never renewed, or renewed in a loop | S3, S4, S5 |
| G7 | A token is used after its stated expiry (no margin) | S4 |
| G8 | A bad key, a missing app id, an unresolved `${…}`, or both kinds loads and fails later | U1, U2 |
| G9 | GitHub's PKCS#1 key is refused | U1 |
| G10 | Every App failure reads as "not found or requires authentication" | S11 |
| G11 | The JWT lives longer than GitHub accepts, or is kept and reused | U3 |
| G12 | A static entry changes behaviour | #498's `UpstreamCredentialsTests` and `UpstreamCredentialsIntegrationTests` stay green unchanged |

## Scenarios

```gherkin
Feature: GitHub App credentials for private upstreams

  # --- unit (GitHubAppTokensTests, UpstreamCredentialsTests; no Spring) ---

  Scenario: U1 keys load in both forms, and nothing else does
    Given a generated RSA key
    Then  its PKCS#1 PEM ("BEGIN RSA PRIVATE KEY") and PKCS#8 PEM ("BEGIN PRIVATE KEY") both load, with surrounding whitespace
    And   bad base64, a truncated body, an EC key, an "ENCRYPTED PRIVATE KEY", a certificate, and plain text
          are refused naming "upstream-credentials[<i>]" and the prefix, and never any line of the key

  Scenario: U2 an unusable App entry stops startup
    Then  each of these is refused naming the entry: github-app with a username or token; neither kind;
          app-id missing, blank, "abc", "0", "${SGW_APP_ID}"; installation-id "x1"; private-key "${SGW_KEY}";
          api-url "http://api.example", "https://u@api.example", "https://api.example?x", "ftp://api.example"
    And   api-url "http://127.0.0.1:<port>" and an omitted api-url load, the latter as https://api.github.com

  Scenario: U3 the assertion
    When  two assertions are signed for app 4242
    Then  each verifies against the public key with RS256, iss "4242", iat = now-60s (±5s), exp - iat <= 600s, exp > now
    And   the two are different strings

  Scenario: U4 the failure classifier
    Then  401 with "'Issued at' claim ('iat') must be an Integer..." or "'Expiration time' claim ('exp') is too far in the future"
          reads as clock skew; 401 with a Date header 5 minutes off reads as clock skew; 401 otherwise reads as a refused key

  Scenario: U6 the properties never print the key
    Then  toString of the GitHubApp record, the credential, the ingestion block and the root properties omits the PEM

  Scenario: U7 owner and repository must be plain names
    Then  "/acme/skills.git", "/acme/skills", "/acme/skills/" yield acme/skills
    And   "/acme", "/acme/a/b", "/ac%20me/x", "/acme/.git", "/acme/..", "/acme/%3F" yield none

  # --- integration (UpstreamGitHubAppIntegrationTests over Spring + two GitHttpFixtures) ---
  # FORGE serves git; API plays GitHub's REST API on another port. Entries:
  #   FORGE/appco/   -> github-app, app 4242, installation omitted, api-url = API
  #   FORGE/pinned/  -> github-app, app 4242, installation-id 77, api-url = API
  #   FORGE/deadapi/ -> github-app, app 4242, installation-id 77, api-url = a closed port

  Scenario: S1 a private upstream registers and ingests with a minted token
    Given the forge requires Basic x-access-token:<T1> under /appco/, and the API issues T1 for installation 55
    When  "FORGE/appco/skills.git" is registered and ingested
    Then  both succeed, every git request under /appco/ carried x-access-token:<T1>,
          the API saw GET /repos/appco/skills/installation then POST /app/installations/55/access_tokens, both with "Bearer <jwt>"
    And   for "FORGE/pinned/skills.git" the API saw no installation lookup, only POST /app/installations/77/access_tokens

  Scenario: S2 the token is scoped
    Then  the POST body is exactly {"repositories":["skills"],"permissions":{"contents":"read"}}

  Scenario: S3 a cached token is reused
    Given the API issues T1 expiring in an hour
    When  the same repository is registered and then ingested twice
    Then  the API saw exactly one token POST for it

  Scenario: S4 a token near expiry is renewed
    Given the API issues T1 expiring in two minutes
    When  the repository is ingested twice
    Then  the API saw a token POST for each read

  Scenario: S5 a refused cached token is renewed once, and only once
    Given T1 is cached, and the forge now wants T2, which the API now issues
    When  it is ingested
    Then  it succeeds, and the API saw exactly one more POST
    Given T2 is cached, the forge now wants T3, and the API still issues T2
    When  it is ingested
    Then  502 "repository not found or requires authentication", and the API saw exactly one more POST

  Scenario: S6 a redirect out of the prefix carries no token
    Given the forge redirects /appco/ requests to another port, and in a second case to /public/
    Then  no request outside /appco/ carries an Authorization header

  Scenario: S7 no git request carries the assertion
    Then  across S1-S6, no forge request carried "Bearer"

  Scenario: S8 the API's redirect is not followed
    Given the API answers the token POST with 302 to FORGE/stolen
    When  it is registered
    Then  502 "the GitHub API did not issue a token", and FORGE never saw /stolen

  Scenario: S9 the API is only the configured one
    Then  across S1-S6, FORGE saw no /repos/ or /app/ request
    And   "FORGE/appco/sk%3Fills.git" and "FORGE/appco/a/b.git" are refused with 502
          "the upstream URL does not name a GitHub repository", and the API saw no request

  Scenario: S10 nothing repeats the key, JWT or token
    Given the forge refuses the token, and an API error message that quotes the issued token
    When  registrations and ingestions succeed and fail
    Then  no API response, marketplace row, fetch_log row or captured log line contains the PEM body,
          any JWT the API saw, or any token it issued

  Scenario: S11 each API refusal has its own reason
    Then  404 on the installation lookup -> "the GitHub App is not installed for this repository"
    And   422 on the token -> the same; 404 on the token -> "the GitHub App installation was not found";
          403 -> "the GitHub App installation is suspended"; 401 -> "the GitHub API refused the App's key";
          401 with an iat message -> "the gateway's clock differs from the GitHub API's";
          /deadapi/ -> "the GitHub API could not be reached"
    And   each answers 502 and creates nothing

  Scenario: S12 the ledger names the kind
    Then  the /appco/ registration's detail contains "credential=FORGE/appco/ (github-app)"
    And   a /private/ (static) registration's contains "credential=FORGE/private/" and no "(github-app)"
```

## Invariants that must survive

- #498's static entries, tests and ledger wording are unchanged.
- No schema change, no API contract change, no new role, no new dependency.
- `ConfigSurfaceBudgetTests` stays at 109; `ContextBudgetTests` does not rise.

## SVC mapping

| SVC | Scenarios |
|---|---|
| SVC_GW_INGEST_0056 — A private upstream registers and ingests with a minted installation token | S1 |
| SVC_GW_INGEST_0057 — The token request names one repository and read-only contents | S2 |
| SVC_GW_INGEST_0058 — A cached token is reused, a near-expiry one renewed, and a refused one renewed once | S3, S4, S5 |
| SVC_GW_INGEST_0059 — Each GitHub API refusal is reported with its own reason | S11, U4 |
| SVC_GW_INGEST_0060 — An unusable GitHub App entry stops startup and names the entry, not the key | U1, U2 |
| SVC_GW_INGEST_0061 — The API is contacted only at the configured URL | S9, U7 |
| SVC_GW_INGEST_0062 — The registration ledger entry names the GitHub App kind | S12 |
| SVC_GW_AUTH_0049 — The key, assertion and installation token appear nowhere | S10, U6 |
| SVC_GW_AUTH_0050 — The assertion verifies against the App's key and expires within ten minutes | U3 |
| SVC_GW_AUTH_0051 — The assertion goes only to the API, never across a redirect or on a git request | S6, S7, S8 |
