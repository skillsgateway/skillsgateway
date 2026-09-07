# Evidence — add-webhook-payload-contract

Commit under test: `PLACEHOLDER_SHA`
Branch: `feat/webhook-payload-contract-impl`, based on `origin/main` at `331cf68`.

Environment (Podman on macOS; see the repository's local-gates notes):

```sh
export TESTCONTAINERS_RYUK_DISABLED=true
export DOCKER_HOST=unix:///var/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

## 1. `./mvnw clean verify`

```
PLACEHOLDER_MVN
```

## 2. `(cd src/main/frontend && pnpm test:stories)`

```
PLACEHOLDER_STORIES
```

## 3. `(cd src/main/frontend && pnpm e2e)`

```
PLACEHOLDER_E2E
```

## 4. `reqstool status local -p docs/reqstool`

```
PLACEHOLDER_REQSTOOL
```

## 5. `openspec validate --all --strict`

```
PLACEHOLDER_OPENSPEC
```

## 6. `mkdocs build --strict`

```
PLACEHOLDER_MKDOCS
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

PLACEHOLDER_RETRIES
