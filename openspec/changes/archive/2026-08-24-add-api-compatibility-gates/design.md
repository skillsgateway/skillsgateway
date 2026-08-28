## Context

See proposal.md — Why.

Facts that shape the approach:

- The OpenAPI document is generated at runtime by springdoc. `OpenApiDocsTests`
  fetches `/v3/api-docs` and writes it to `target/openapi.json`; the copy at
  `src/main/frontend/openapi.json` is made **by hand** and drives
  `openapi-typescript` and the MSW mocks (ADR 0003).
- The Maven version is derived from git state by Nisse (ADR 0002) and currently
  resolves to `0.1.0-82-SNAPSHOT`, where `82` is the commit distance. **It
  changes on every commit.**
- `.github/workflows/ci.yml` already checks out with `fetch-depth: 0` because
  Nisse needs history; a merge-base baseline needs the same.
- `PackagingTests` establishes the repository's pattern for making a CI contract
  testable: a JUnit test that asserts the workflow file carries the contract,
  annotated with `@SVCs` so reqstool can trace it.
- The PR title becomes the squash commit subject
  (`squash_merge_commit_title: PR_TITLE`) and `check-semantic-pr.yml` already
  validates its conventional-commit type.
- There are no tags and no release automation.

## Goals / Non-Goals

**Goals:**

- A breaking change to the contract cannot merge silently — it either fails, or
  it is declared in the one string that will drive the version.
- The committed contract is trustworthy enough to be a diff baseline.
- The rule is readable at the moment it is violated, not only in a docs page.

**Non-Goals:**

- Enforcing that the prefix *was* moved. Nothing can check "you should have
  versioned this"; only "you broke something". That half stays documentation and
  review.
- Computing the release version from commits. No release automation exists yet;
  this change makes the input to it correct.
- Guarding any contract other than the REST one (see proposal — Not in this
  change).

## Decisions

### The drift check is a JUnit test, not a CI step

`OpenApiContractTests` asserts that `src/main/frontend/openapi.json` equals the
document the running application serves. It fails with the regenerate command in
the assertion message.

Why not a shell step in `ci.yml` diffing two files: a test runs in
`./mvnw verify` locally, so the failure arrives before the push rather than
after; it carries an `@SVCs` annotation so reqstool traces the requirement; and
CI gets it for free through the existing `gates` job. It also matches
`PackagingTests`, which already asserts repo-level contracts from Java.

### `info.version` is the real version when served, a placeholder when committed

The served document (`/v3/api-docs`) reports the Nisse-derived version, so a
consumer reading the document learns which release it describes. An
`OpenApiCustomizer` sets it from Spring Boot's `BuildProperties`, which requires
adding the `build-info` goal to `spring-boot-maven-plugin`. The bean takes
`ObjectProvider<BuildProperties>` and falls back to a constant, so an IDE run
without `build-info` still starts.

The committed snapshot cannot carry that value: it changes every commit, and the
drift check would be red on every PR. `OpenApiDocsTests` therefore writes
`target/openapi.json` in a **canonical form** — the served document with
`info.version` replaced by `0.0.0` — and that file remains what gets copied to
`src/main/frontend/openapi.json`. The regenerate instruction is unchanged; the
comparison is a plain equality check.

Nothing is lost: `openapi-typescript` does not read `info.version`, and oasdiff
compares paths and schemas.

Alternative considered: keeping `info.version` static at `v1` and versioning
nothing. Rejected — it is the decision that makes "this diff is breaking" and
"the version went major" comparable facts, which is the whole point of the
commit-type check below.

### The canonical form is serialised deterministically

Written with sorted object keys and stable indentation. springdoc assembles the
document from reflection over the application context, and any ordering
instability would make the committed file flap and the drift test flaky. Sorting
removes the question rather than betting the current order is stable. This
rewrites the committed document once (formatting only).

### The baseline is the merge base with `main`

`git merge-base origin/main HEAD`, then
`git show <base>:src/main/frontend/openapi.json`. Not the tip of `main`: a PR
would otherwise be blamed for a breaking change another PR merged after it
branched.

The baseline a deployed client actually cares about is the document at the last
released tag. There are no tags, so that baseline does not exist yet — see Open
Questions.

### A deliberate breaking change escapes via a PR label

Failing is right, but breaking changes are *allowed* — they cost a prefix move
and a major. The escape is a `⚠️ BREAKING CONTRACT` label on the PR, mirroring
the existing `⚠️ TRUST BOUNDARY` convention.

Why a label rather than an ignore entry in `.oasdiff.yaml`: a label is visible in
review, is scoped to the one PR, and disappears with it. A checked-in ignore
silently keeps suppressing the same class of change in every later PR — the
failure mode where a gate is still green long after it stopped meaning anything.

Note that moving to `/api/v2` while keeping `/api/v1` is purely additive and
passes with no label at all. The label is needed only when the old surface is
removed — which is exactly the moment that deserves a deliberate act.

### The gate and the PR title must agree

One-directional, in a single job:

| Breaking diff | Label | Title declares breaking | Result |
| --- | --- | --- | --- |
| no | — | — | pass |
| yes | absent | — | **fail** — message names the label and the prefix rule |
| yes | present | no | **fail** — declared breaking, but the commit that ships it does not say so |
| yes | present | yes | pass |

Not the converse: a title may declare a breaking change with no breaking REST
diff, because a break can live in configuration or behaviour the OpenAPI
document does not describe.

### A dedicated workflow, `api-contract.yml`

Rather than a job inside `ci.yml`. It runs only on `pull_request` (the merge base
and the title only exist there), needs no build, and — like
`check-semantic-pr.yml` — must trigger on `edited`, or a title corrected after a
red check leaves the check red on a PR that is now right. Keeping it separate
also makes the `@SVCs` test that asserts its contract easy to read.

## Risks / Trade-offs

- **springdoc emits a non-deterministic document** → canonical serialisation with
  sorted keys; if it still flaps, the drift test surfaces it immediately rather
  than the portal's types quietly rotting.
- **`build-info.properties` missing in the native image** → the native workflow
  builds and smoke-tests the image; the `ObjectProvider` fallback means a missing
  file degrades the served version rather than failing startup. Verify the
  version in the native smoke test.
- **The label escape is abusable** — anyone who can label can ship a break →
  mitigated, not eliminated: it is visible in review, it additionally forces the
  title to declare the break, and both are permanent in the PR record.
- **oasdiff classifies something breaking that we consider additive** → the
  escape exists and is documented; if a class of false positive recurs, that is
  when a checked-in `.oasdiff.yaml` exception earns its keep, with a comment
  saying why.
- **Committed document rewritten once** → a large, noisy diff in the PR that
  introduces the canonical form. Unavoidable, and it happens exactly once.

## Migration Plan

No runtime migration. Order within the PR: canonical form and drift test first
(so the baseline is trustworthy), then the version customiser, then the workflow,
then the docs. Rollback is a revert; nothing persists outside the repository.

## Open Questions

- **When the first tag exists, should the gate also diff against the last
  released document?** That is the baseline a deployed client cares about. It can
  be added as a second invocation without changing anything decided here, so it
  is deferred to the release that creates the first tag.
- **Where the `⚠️ BREAKING CONTRACT` label is declared.** The repository has no
  `.github/settings.yml`; how declarative repo settings run is itself open
  (#116). Until that is settled the label is created directly, as the existing
  labels were.
