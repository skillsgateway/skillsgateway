# Evidence: dev-insecure-auth-guard

One final fresh run of every gate after the last code edit, against a clean tree
at `45f9dfe1555f0059b0e903857e35b4a58f3af44a`. The only edit after it is this
report — no source, docs or requirements changes.

## Discipline notes (old-coder — this change hardens the web-surface auth boundary)

- **Spec approval: not obtained (autonomous run).** The brief was "add a NOTICE
  file, and make the dev escape hatch fail fast where it obviously does not
  belong, choosing the signal yourself and justifying it". The committed
  OpenSpec change — `proposal.md`, `design.md`, `specs/auth/spec.md`,
  `tasks.md` — is the spec the implementation was held to, and it is offered for
  review after the fact rather than before.
- **RED was observed before GREEN.** See below: the guard's refusal was disabled,
  the suite watched failing for the right reason, then restored and watched
  passing.
- **No existing SVC test was weakened.** One pre-existing test was edited, and
  only by addition: `DevAuthTests` now autowires the guard and asserts it saw no
  identity-provider signals. Its original assertions are untouched.
- **Direction of the guard's failure.** It fails closed in the safe direction:
  a false positive refuses a start (loud, recoverable, message names the fix); a
  false negative leaves today's behaviour exactly as it is. It can therefore
  make nothing worse than the status quo, which is why the residual gap — a
  deployment with no identity provider at all is indistinguishable from a laptop
  — is documented rather than compensated for by a riskier signal.

## RED then GREEN

The guard's refusal was disabled at
`src/main/java/dev/skillsgateway/server/auth/DevInsecureAuthGuard.java` by
changing `if (!signals.isEmpty())` to `if (false && !signals.isEmpty())`, so the
signals are still collected and simply never acted on.

```console
$ ./mvnw -Dtest=DevInsecureAuthGuardTests -DfailIfNoSpecifiedTests=false test
[INFO] Running dev.skillsgateway.server.DevInsecureAuthGuardTests
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.DevInsecureAuthGuardTests
[ERROR] dev.skillsgateway.server.DevInsecureAuthGuardTests.the_escape_hatch_refuses_to_start_where_an_identity_provider_is_configured -- <<< FAILURE!
Expecting:
	at dev.skillsgateway.server.DevInsecureAuthGuardTests.assertRefused(DevInsecureAuthGuardTests.java:102)
	at ...lambda$the_escape_hatch_refuses_to_start_where_an_identity_provider_is_configured$1(DevInsecureAuthGuardTests.java:61)
[INFO] BUILD FAILURE
```

The failure is at the first refusal case (a real client id) and at
`assertRefused` — the context started when it should not have — which is the
reason the test claims to check, not an incidental error.

Restored:

```console
$ ./mvnw -Dtest=DevInsecureAuthGuardTests -DfailIfNoSpecifiedTests=false test
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in dev.skillsgateway.server.DevInsecureAuthGuardTests
[INFO] BUILD SUCCESS
```

## Gates

### `./mvnw clean verify`

```console
$ DOCKER_HOST=unix://…/podman-machine-default-api.sock TESTCONTAINERS_RYUK_DISABLED=true ./mvnw clean verify
[INFO] Tests run: 184, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 172 files clean - 0 needs changes to be clean
[INFO] --- checkstyle:3.6.0:check (default) @ skills-gateway-server ---
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  01:23 min
```

### `(cd src/main/frontend && pnpm test:stories)`

```console
 ✓ |storybook (chromium)| src/components/markdown-view.stories.tsx (2 tests) 347ms
 ✓ |storybook (chromium)| src/pages/tokens.stories.tsx (1 test) 224ms
 ✓ |storybook (chromium)| src/pages/adoption.stories.tsx (3 tests) 211ms

 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### `(cd src/main/frontend && pnpm e2e)`

Runs the packaged jar against a real mock OIDC identity provider — the
configuration the guard would refuse if the escape hatch were also on. It is
not, so the suite is unaffected, which is the compatibility claim being tested.

```console
  ✓  11 [chromium] › e2e/portal.spec.ts:489:1 › preview_pane_shows_tree_inert_skill_md_and_diff_vs_served (1.4s)
  ✓  12 [chromium] › e2e/portal.spec.ts:572:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (283ms)

  12 passed (25.1s)
```

### `reqstool status local -p docs/reqstool`

```console
  GW_0108             skills-gateway
  GW_0109             skills-gateway
  GW_0110             skills-gateway

INCOMPLETE (0)
107/107 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```console
✓ change/dev-insecure-auth-guard
…
Totals: 27 passed, 0 failed (27 items)
```

### `mkdocs build --strict`

```console
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: …/site
INFO    -  Documentation built in 0.89 seconds
```
