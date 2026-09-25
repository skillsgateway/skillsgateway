# Tasks: upstream-github-app

This change adds a credential kind to the registration trust boundary, so the
work is done under `.claude/skills/old-coder` (Tier 3). The acceptance spec is
`acceptance.md`. Each new test is shown failing first, and the failures are
recorded in `evidence.md`.

## 1. Requirements and acceptance spec

- [x] 1.1 Add GW_INGEST_0056 to GW_INGEST_0062, GW_AUTH_0049 to GW_AUTH_0051 and their SVCs to `docs/reqstool/`, and verify that `reqstool status` lists them
- [x] 1.2 Write `acceptance.md` (failure model, scenarios, setup plan), and verify that every SVC maps to at least one scenario

## 2. Tests first (shown failing)

- [x] 2.1 `GitHttpFixture`: answer a JSON route with any status, for any method, and record every request's method, path, `Authorization` and body. Verify that existing fixture users still pass
- [x] 2.2 `GitHubAppTokensTests` (plain JUnit, SVC_GW_INGEST_0060, SVC_GW_AUTH_0050, SVC_GW_AUTH_0049): PKCS#1 and PKCS#8 keys load, malformed and non-RSA keys refuse, both kinds / missing app-id / unresolved `${…}` / non-https `api-url` refuse; the JWT's claims and signature; `toString` omits the key; the failure classifier (clock skew from message and `Date`). Verify each fails against a stub
- [x] 2.3 `UpstreamGitHubAppIntegrationTests` (SVC_GW_INGEST_0056 to SVC_GW_INGEST_0059, SVC_GW_INGEST_0061, SVC_GW_INGEST_0062, SVC_GW_AUTH_0049, SVC_GW_AUTH_0051): registers and ingests with a minted token; resolves the installation; the request body names only the one repository and `contents: read`; the cached token is reused, a near-expiry one is renewed, a refused cached one is renewed exactly once; translated reasons; redirects carry no token; the API is never contacted at a URL-derived host and a hostile repository name never reaches it; nothing repeats the key, JWT or token; the ledger names the kind. Verify they fail against the stub

## 3. Implementation

- [x] 3.1 `SkillsGatewayProperties.UpstreamCredential.githubApp` and its record with a key-free `toString`; verify `ConfigSurfaceBudgetTests` still passes at 109 and record why in its comment
- [x] 3.2 `GitHubAppTokens`: key loading, JWT, installation lookup, token exchange, cache, failure translation (GW_INGEST_0056, GW_INGEST_0057, GW_INGEST_0058, GW_INGEST_0059, GW_INGEST_0061, GW_AUTH_0049, GW_AUTH_0050, GW_AUTH_0051). Verify the 2.2 tests pass
- [x] 3.3 `UpstreamCredentials`: validate the new kind (GW_INGEST_0060), `access(url, renew)`, secrets include PEMs and cached tokens; `UpstreamGit`: resolve access per operation, one renewal on a refused cached token. Verify the 2.3 tests pass
- [x] 3.4 `MarketplaceRegistrationService`: `(github-app)` in the ledger detail (GW_INGEST_0062)
- [x] 3.5 Run the manual mutation runner (`mutants.sh` in the change), and verify that every mutant is killed

## 4. Documentation

- [x] 4.1 `guides/private-upstreams.md`: a GitHub App section (why, create and install, local, Helm, ECS/Fargate, rotation, failures). Verify with `mkdocs build --strict`
- [x] 4.2 `reference/configuration.md`, `concepts/trust-boundaries.md`, the capability map's Registration row. Verify with `mkdocs build --strict`

## 5. Gates and evidence

- [x] 5.1 Run all six gates from CLAUDE.md fresh after the last code edit, and record them with the SHA in `evidence.md`
- [x] 5.2 Archive the change as the PR's final commit, and verify with `openspec validate --all --strict`
