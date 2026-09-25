# Tasks: upstream-credentials

This change adds a credential to the registration trust boundary, so the work
is done under `.claude/skills/old-coder` (Tier 3). The acceptance spec is
`acceptance.md`. Each new test is shown failing first, and the failures are
recorded in `evidence.md`.

## 1. Requirements and acceptance spec

- [x] 1.1 Add GW_INGEST_0050 to GW_INGEST_0055 and their SVCs to `docs/reqstool/`, and verify that `reqstool status` lists them
- [x] 1.2 Write `acceptance.md` (scenarios, failure model, setup plan), and verify that every SVC maps to at least one scenario

## 2. Tests first (shown failing)

- [x] 2.1 `GitHttpFixture`: `requireBasic(prefix, user, token)`, and a record of every request's `Authorization` header. Verify that existing fixture users still pass
- [x] 2.2 `UpstreamCredentialsTests` (plain JUnit): matching, segment boundary, longest prefix, non-canonical paths, startup validation, `toString`. Verify each fails against a stub
- [x] 2.3 `UpstreamCredentialsIntegrationTests`: a private upstream registers and ingests; a wrong token is refused as not-found-or-auth; redirects to another port, another path and another credentialed prefix carry no token; the token appears in no response, ledger, row, `fetch_log` or log. Verify that the redirect tests fail against a JGit `CredentialsProvider` implementation
- [x] 2.4 Registration tests: userinfo refused with `400` and nothing created; the ledger detail names the prefix. Verify that both fail before the fix

## 3. Implementation

- [x] 3.1 `SkillsGatewayProperties.Ingestion.upstreamCredentials` and the `UpstreamCredential` record with a token-free `toString`, and verify with `ConfigSurfaceBudgetTests` at 109 and its argument in the comment
- [x] 3.2 `UpstreamCredentials`: validation at startup, matching, and the per-request connection factory. Verify that the 2.2 tests pass
- [x] 3.3 `UpstreamGit.connect(command, url)` installs the factory; `UpstreamFailure.scrub`. Verify that the 2.3 tests pass
- [x] 3.4 `MarketplaceRegistrationService`: refuse userinfo; add `credential=<prefix>` to the ledger detail. Verify that the 2.4 tests pass
- [x] 3.5 Run the manual mutation runner (`mutants.sh` in the change), and verify that every mutant is killed

## 4. Documentation

- [x] 4.1 `reference/configuration.md`: the new list, its validation, rotation by restart. Verify with `mkdocs build --strict`
- [x] 4.2 `guides/registering-a-marketplace.md`: private upstreams, the token-scope warning, and examples for local development, Helm (`config` plus `extraEnv` with `secretKeyRef`) and ECS/Fargate (`secrets` from Secrets Manager, `secretsmanager:GetSecretValue` on the execution role). Verify with `mkdocs build --strict`
- [x] 4.3 `concepts/trust-boundaries.md` and the capability map's Registration row. Verify with `mkdocs build --strict`

## 5. Gates and evidence

- [ ] 5.1 Run all six gates from CLAUDE.md fresh after the last code edit, and record them with the SHA in `evidence.md`
- [ ] 5.2 Archive the change as the PR's final commit, and verify with `openspec validate --all --strict`
