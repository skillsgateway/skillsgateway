# Evidence: require-oidc-issuer

- **Source state:** `8a62af38` (last code edit). Every result below is from one
  fresh run of each gate at that commit, after that edit.
- **Tier:** 3 (authentication trust boundary), `.claude/skills/old-coder`.
- **Spec approval:** not obtained (autonomous run). The decision itself (refuse,
  no opt-out leaf, chart `required`) was taken for the owner on #546; the spec is
  the "Old-coder SPEC" section of `design.md`.
- **Independent verification:** not performed.
- **Isolation:** git worktree `.claude/worktrees/oidc-issuer` from `origin/main`.
- **Tools:** OpenJDK 25.0.1, openspec 1.13.2, mkdocs 1.6.1, helm v3.19.0 (a
  throwaway binary in the session scratchpad, not installed on the machine).

## Scenario → test

| Spec | Test (`@SVCs SVC_GW_AUTH_0017.2`) | Result |
|---|---|---|
| S1 real client id, no issuer → refuses, names property, signal, both ways out | `OidcIssuerRequiredTests.a_configured_provider_without_an_expected_issuer_refuses_to_start` | passed |
| S2 real `jwk-set-uri` only → refuses | same method | passed |
| S3 whitespace issuer → refuses | same method | passed |
| S4 provider + issuer → starts; the login decoder accepts the pinned issuer and refuses another tenant's token signed by the same keys | `…a_configured_provider_with_an_expected_issuer_starts_and_its_login_compares_it` | passed |
| S5 placeholders, no issuer → starts | `…the_unconfigured_state_and_the_escape_hatch_start_without_an_issuer` | passed |
| S6 escape hatch, placeholders, no issuer → starts | same method | passed |
| S7 escape hatch + provider, with the hatch guard → the hatch's refusal, not the issuer's | `…under_the_escape_hatch_a_configured_provider_is_refused_by_the_hatch_guard` | passed |
| S8 escape hatch + provider, no hatch guard → this check stands aside | same method | passed |
| Chart renders no deployment without `oidc.issuer` | `PackagingTests.chartRequiresTheExpectedIssuer` + `helm template` below | passed |

Invariants: no existing SVC test changed an assertion (`OidcIdTokenValidationTests`
changed one comment); `ContextBudgetTests` and `ConfigSurfaceBudgetTests` pass
with their budgets untouched; no OpenAPI change (`OpenApiContractTests` green in
`verify`).

## RED

- `OidcIssuerRequiredTests` against a stub that moved the old warn-only bean
  unchanged: `Tests run: 4, Failures: 1` —
  `a_configured_provider_without_an_expected_issuer_refuses_to_start` failed
  (`assertRefused`, context started).
- `PackagingTests` before the chart edit: `Tests run: 13, Failures: 1` —
  `chartRequiresTheExpectedIssuer` ("to contain pattern").

**Deviation from tasks.md 2.1, recorded rather than rewritten:** S2, S3 and S7
were not observed failing individually. S2 and S3 sit in the S1 method after
the first failing assertion; S4–S8 describe behaviour that already held on the
stub (the stub never refused, so S5–S8 pass by construction, and S4's issuer
wiring predates this change). Each is instead proved non-vacuous by a mutant
below: S3 by M2, S7/S8 by M3, S4 by M5, S5/S6 by M6.

**A test defect the mutation run found:** M2 (`isBlank` → `isEmpty`) survived the
first run. `withPropertyValues("key=   ")` trims the value, so S3 was testing an
empty issuer. Fixed in `8a62af38` by setting the value through a
`MapPropertySource`; M2 is killed on the rerun below.

## Gauntlet

| Layer | Command | Result |
|---|---|---|
| Full suite + Spotless + Checkstyle + UI gate + jar | `./mvnw clean verify` | `Tests run: 998, Failures: 0, Errors: 0, Skipped: 9` · `BUILD SUCCESS` · Total time 07:28 min |
| Story tests | `(cd src/main/frontend && pnpm test:stories)` | `Test Files 17 passed (17)` · `Tests 88 passed (88)` |
| E2E, real login through the mock provider with the issuer compared | `(cd src/main/frontend && pnpm e2e)` | `24 passed (1.2m)` |
| Traceability | `reqstool status local -p docs/reqstool` | `324/324 complete · 0 incomplete · PASS`; SVC_GW_AUTH_0017.2 ← 5 passing tests |
| OpenSpec | `openspec validate --all --strict` | `Totals: 31 passed, 0 failed (31 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 1.48 seconds` (two pre-existing INFO anchor notes, unrelated) |
| Mutation | `openspec/changes/require-oidc-issuer/mutants.sh` | `8 mutants run, all killed` |
| Mutation runner negative control | `ONLY=M99 mutants.sh` | `no mutant selected`, exit 5 |
| Chart | `helm template` without / with `oidc.issuer` | see below |
| Real execution | the packaged jar against a throwaway PostgreSQL | see below |
| Types / lint | `javac` via verify; `tsc -b` and `oxlint` via the UI gate | clean |
| Coverage on changed lines | not measured by a tool; every branch of `idTokenDecoderFactory` is killed by a mutant (M1–M7) | — |
| Property-based tests | skipped: the input is a small closed set of configuration shapes, enumerated in S1–S8 | — |
| Supply chain | no dependency added or changed | — |

Mutation (M1–M7 target `IdTokenDecoderConfiguration`, M8 the chart):

```
M1-no-refusal killed
M2-whitespace-issuer-counts-as-set killed
M3-hatch-does-not-defer killed
M4-registrations-ignored killed
M5-issuer-not-wired-to-login killed
M6-refuses-the-unconfigured-state killed
M7-hatch-condition-inverted killed
M8-chart-issuer-optional killed
8 mutants run, all killed
```

Chart (`BASE` sets the other required values and `persistence.mode=ephemeral`):

```
$ helm template t helm/skills-gateway $BASE
Error: execution error at (skills-gateway/templates/deployment.yaml:87:24): oidc.issuer is required: the identity token's issuer is the tenant boundary
rc=1
$ helm template t helm/skills-gateway $BASE --set oidc.issuer=https://idp.example.com/v2.0 | grep -A1 SKILLSGATEWAY_OIDC_ISSUER
            - name: SKILLSGATEWAY_OIDC_ISSUER
              value: "https://idp.example.com/v2.0"
rc=0
$ helm lint helm/skills-gateway
1 chart(s) linted, 0 chart(s) failed
```

**Deviation from tasks.md 3.2:** `helm lint` does not fail without the issuer.
Helm's lint mode reports a missing `required` value as INFO and continues, as it
already does for `postgresql.host` and `oidc.existingSecret`; that is what keeps
the CI `Helm lint` step (default values) green. `helm template`/`install` is
where the refusal lands.

Real execution (`java -jar target/skills-gateway-server-*.jar`, PostgreSQL in a
throwaway container, removed afterwards):

```
### provider, no issuer
exit=1
... Factory method 'idTokenDecoderFactory' threw exception with message: skills-gateway.oidc.issuer is not set, and this gateway has an identity provider configured:
  - the OIDC client registration 'idp' carries a real client id
  - the OIDC provider 'idp' has a real authorization-uri (http://localhost:9090/default/authorize)
### provider + issuer
STARTED
### placeholders, no issuer
STARTED
```

The mock provider's issuer: the e2e run above logs in through
`http://localhost:9090/default` with `SKILLSGATEWAY_OIDC_ISSUER` set to exactly
that, and the login's issuer comparison would refuse any other `iss`, so the 24
passing specs are the proof that the string matches. The container smoke test
talks to the same mock on the same host and port.

## Known limits

- A `ClientRegistrationRepository` that is not iterable (a custom bean) yields
  no signals, so the check would not fire; the escape-hatch guard has the same
  limit. The application ships only Boot's in-memory repository.
- Configuring the provider through `issuer-uri` discovery alone still requires
  `skills-gateway.oidc.issuer`; the docs already say that shape does not work
  with the shipped placeholders.
