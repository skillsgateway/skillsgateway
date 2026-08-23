## Why

Nothing checks that a change to `/api/**` keeps faith with the clients already
consuming it. The compatibility rule has never been written down, the OpenAPI
document declares a hand-written `info.version: v1` that has never changed and
identifies no release, and the committed contract copy the portal's types are
generated from is refreshed by hand with nothing verifying it still matches what
the build produces. A breaking change can ship today and pass every gate.

The repository has no tags and no releases, so breaking the contract currently
costs nothing — which is exactly why the gate is cheap now and expensive after
the first release, when the rule acquires consequences on the same day it
acquires clients. Issue #121.

## What Changes

- The compatibility promise is stated: within a major, changes to `/api/**` are
  additive; a breaking change moves the path prefix **and** ships as a major.
- The served OpenAPI document declares the real release version (semver, derived
  from git state) instead of the static `v1`.
- The committed contract document (`src/main/frontend/openapi.json`) is verified
  against the document the build generates, so the portal's generated types and
  the breaking-change baseline can be trusted. Its `info.version` is normalised
  to a fixed placeholder so a version that moves every commit does not make the
  committed copy churn.
- Pull requests gain a breaking-change gate: the contract is diffed against the
  merge base with `main`, a breaking classification fails the check, and the
  failure states the escape rather than only refusing.
- A breaking diff must be declared as one: the PR title's conventional-commit
  type has to carry `!` or `BREAKING CHANGE:`, since that title becomes the
  squash commit subject and will drive the version once release automation
  exists.

Not in this change, deliberately:

- **The `/api/v1` prefix itself.** Endpoints are unversioned today
  (`/api/marketplaces`). Introducing the prefix via Spring Framework 7's API
  versioning (ADR 0002) touches every controller, the portal client, the MSW
  mocks, e2e and docs. It is the first change the new gate should be exercised
  on, not part of building the gate.
- **A contract document for the webhook lifecycle event payloads.** The payloads
  are a second contract with no document at all, but AsyncAPI (a format and a
  tool this repository does not use) versus generated JSON Schemas is still
  undecided (#121).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-api`: three new requirements — the contract document declares the
  release version (GW_0105), the committed contract stays identical to the
  generated one (GW_0106), and breaking changes to the contract are detected and
  must be declared (GW_0107).

## Impact

- **Requirements**: `docs/reqstool/requirements.yml` gains GW_0105–GW_0107 and
  their SVCs. Ids through GW_0104 are already allocated; GW_0105 is the next free id.
- **Build**: `spring-boot-maven-plugin` gains the `build-info` goal so
  `BuildProperties` carries the version into the served document.
- **Java**: a new `OpenApiCustomizer` sets `info.version`; `OpenAPI.java` drops
  its literal `version = "v1"`. New contract tests alongside `OpenApiDocsTests`.
- **CI**: a new workflow (or job) running `oasdiff` on pull requests, plus the
  title-agreement check; a checked-in `.oasdiff.yaml`.
- **Repo settings**: a new PR label for a declared breaking contract change,
  alongside the existing `⚠️ TRUST BOUNDARY` label.
- **Docs**: `docs/manual/reference/compatibility.md` gains the promise; a
  pointer line in `CLAUDE.md`; the regenerate/drift rule joins the existing
  `openapi.json` → `types.gen.ts` convention in
  `.claude/skills/code-conventions/SKILL.md`.
- **Committed contract**: `src/main/frontend/openapi.json` is rewritten once with
  the normalised `info.version`.
