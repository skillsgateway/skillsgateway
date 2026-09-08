# Evidence — add-webhook-payload-contract

Commit under test: `ddc2c85`
Branch: `feat/webhook-payload-contract-impl`, based on `origin/main` at `331cf68`.

Environment (Podman on macOS; see the repository's local-gates notes):

```sh
export TESTCONTAINERS_RYUK_DISABLED=true
export DOCKER_HOST=unix:///var/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## 1. `./mvnw clean verify`

```
$ ./mvnw clean verify
[INFO] Results:
[INFO] Tests run: 590, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  3 passed (3)
      Tests  6 passed (6)
JUNIT report written to .../src/main/frontend/test-results/storybook-junit.xml
EXIT=0
```

## 3. `(cd src/main/frontend && pnpm e2e)`

```
$ (cd src/main/frontend && pnpm e2e)
  ✓  13 [chromium] › e2e/portal.spec.ts:665:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (346ms)

  13 passed (42.4s)
normalized classnames in .../src/main/frontend/test-results/playwright-junit.xml
EXIT=0
```

## 4. `reqstool status local -p docs/reqstool`

```
$ reqstool status local -p docs/reqstool
  GW_0181             skills-gateway
  GW_0182             skills-gateway

INCOMPLETE (0)
167/167 complete · 0 incomplete · PASS
```

## 5. `openspec validate --all --strict`

```
$ openspec validate --all --strict
✓ spec/virtual-catalog
Totals: 32 passed, 0 failed (32 items)
```

## 6. `mkdocs build --strict`

```
$ mkdocs build --strict
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.89 seconds
```

## 7. The gate, exercised against the pinned oasdiff binary

The workflow's `oasdiff/oasdiff-action/breaking@54d7a75…` (v0.1.14) runs
`tufin/oasdiff:v1.29.1`. Run directly against that image, with this repository's
own `.oasdiff-severity-levels.txt`, so the classifications below are the ones CI
will produce and not an approximation of them.

`base-main.json` is `git show origin/main:src/main/frontend/openapi.json` — the
fork point the workflow resolves. `rev-pr.json` is this branch's published
document. The last three revisions mutate `rev-pr.json` so each effect is
isolated from the endpoint change.

### 7.1 This PR's own diff — the declared break

```
$ oasdiff breaking base-main.json rev-pr.json --severity-levels … --fail-on ERR
1 changes: 1 error, 0 warning, 0 info
error	[response-body-type-changed] at /specs/rev-pr.json
	in API GET /api/webhooks/events
		the response's body `type` changed from `array<string>` to `object` for status `200`
EXIT=1
```

This is why the PR carries the `⚠️ BREAKING CONTRACT` label and a `!` title. It
is also the first real exercise of the escape path the gate documents.

### 7.2 An event removed from the registry → error

`webhooks['snapshot.rejected']` deleted:

```
1 changes: 1 error, 0 warning, 0 info
error	[webhook-removed]
	in components/webhooks
		webhook `snapshot.rejected` removed
EXIT=1
```

### 7.3 A payload field renamed (`sha` → `commit`) → error, from the `paths` reference

```
17 changes: 9 error, 8 warning, 0 info
error	[response-required-property-removed] at /specs/rev-field-renamed.json
	in API GET /api/webhooks/events
		removed the required property `examplePayload/sha` from the response with the `200` status
error	[new-required-request-property] at
	in API POST webhook:snapshot.approved
		added the new required request property `commit`
	… (one per event)
EXIT=1
```

This is the case the design exists to fix. From the `webhooks` entries alone the
removal is `request-property-removed`, a **warning**, which `fail-on: ERR` lets
through; reaching the same schema through the events endpoint's `200` response
makes it `response-required-property-removed`, an error.

### 7.4 Genuinely additive — a new optional payload field and a new event → clean

```
No breaking changes to report, but the specs are different.
EXIT=0
```

Which is the measurement behind the rule stated in the guide and in
compatibility.md: a payload field added later is added **optional**. Added
*required*, it would fire `new-required-request-property` on every event, as
7.3's second finding shows.

## Retries

**Four runs of `./mvnw clean verify` were needed, and the first three are not
evidence of anything about this change.** They failed with 77, 189 and 10 errors
respectively — zero assertion failures in all three, every error a Spring
`ApplicationContext` load failure whose root cause was, verbatim:

```
com.github.dockerjava.api.exception.InternalServerErrorException: Status 500:
  {"cause":"something went wrong with the request: \"proxy already running\n\"", …}
```

That is Podman's rootless port-forwarding proxy colliding when several Maven runs
start containers at once — up to 71 containers were live on this machine at one
point, belonging to four agents working in parallel. Every failing report was
checked individually against that signature; none was an assertion.

The fourth run was taken under a shared FIFO gate mutex, with no other suite
running: **BUILD SUCCESS, 590 tests, 0 failures, 0 errors**. `pnpm e2e` was run
under the same mutex. `pnpm test:stories`, `reqstool status`, `openspec validate`
and `mkdocs build` need no containers and were run unlocked.

Independent corroboration: the same commit's CI run on clean GitHub runners
reported **Build & gates**, **Portal e2e**, **Storybook tests** and
**Documentation (strict)** green on the first attempt.

One further failure was **real** and is fixed rather than retried: the first CI
run reported `166/167 complete · 1 incomplete · FAIL` because GW_0182 carried no
`@Requirements` annotation. See the PR body — "A note on the traceability gate".
The reqstool run above is after that fix.
