## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0105 (served document declares the release version), GW_0106
      (committed contract identical to the generated one), GW_0107 (breaking
      contract changes detected and declared) to `docs/reqstool/requirements.yml`
      with title, significance, description, rationale, categories, revision —
      matching the style of the surrounding entries.
- [x] 1.2 Add SVC_GW_0105–SVC_GW_0107 to the reqstool SVC file alongside the
      existing SVCs.

## 2. The contract document: canonical form and version

- [x] 2.1 Add the `build-info` goal to `spring-boot-maven-plugin` in `pom.xml`.
- [x] 2.2 Add an `OpenApiCustomizer` bean setting `info.version` from
      `ObjectProvider<BuildProperties>`, falling back to a constant when
      `build-info.properties` is absent; remove `version = "v1"` from
      `@OpenAPIDefinition` in `OpenAPI.java`.
- [x] 2.3 Add a canonical serialiser (sorted keys, stable indentation,
      `info.version` → `0.0.0`) and make `OpenApiDocsTests` write
      `target/openapi.json` through it.
- [x] 2.4 Regenerate `src/main/frontend/openapi.json` from the canonical output
      and regenerate `src/api/types.gen.ts`; frontend typecheck. Expect a large
      formatting-only diff (design — Risks).

## 3. Tests

- [x] 3.1 `OpenApiContractTests`: the served document's `info.version` equals the
      build's version and is not the literal `v1` (`@SVCs SVC_GW_0105`).
- [x] 3.2 `OpenApiContractTests`: `src/main/frontend/openapi.json` equals the
      canonical form of the served document, failing with the regenerate command
      in the message (`@SVCs SVC_GW_0106`).
- [x] 3.3 Negative test for 3.2: a mutated committed document fails the check —
      prove the test can fail before trusting it.
- [x] 3.4 `OpenApiContractTests`: `.github/workflows/api-contract.yml` carries the
      gate's contract — merge-base baseline, `fetch-depth: 0`, the label escape,
      the title-agreement rule, and `types: [... edited ...]` — in the style of
      `PackagingTests.releaseWorkflowCarriesThePublishByDigestContract`
      (`@SVCs SVC_GW_0107`).

## 4. The CI gate

- [x] 4.1 Create `.github/workflows/api-contract.yml`: `on: pull_request` with
      `types: [opened, edited, reopened, synchronize]`, checkout with
      `fetch-depth: 0`, resolve `git merge-base origin/main HEAD` and extract the
      baseline document from it.
- [x] 4.2 Run oasdiff (official action, pinned in the repository's `@vN` style)
      against baseline and head; confirm whether it reads a checked-in
      `.oasdiff.yaml` and add one only if it does — otherwise keep the flags in
      the workflow rather than inventing an unread config file.
      **Outcome**: it does read one (severity-levels), but there is nothing to
      configure — `fail-on: ERR` in the workflow is the whole policy — so no
      file was added. `.oasdiff.yaml` is where a recurring false positive goes.
- [x] 4.3 Implement the decision table from design.md (breaking diff × label ×
      title), with failure messages that state the escape: keep `/api/v1`
      alongside the new prefix, or label the PR `⚠️ BREAKING CONTRACT` and
      declare the break in the title.
- [x] 4.4 Create the `⚠️ BREAKING CONTRACT` label (`gh label create`), matching
      the colour convention of `⚠️ TRUST BOUNDARY`.
- [x] 4.5 Add `.github/workflows/api-contract.yml` and `.oasdiff.yaml` (if used)
      to `.github/labeler.yml` under the `ci` label. **Outcome**: no change
      needed — `ci` already globs `.github/**`.

## 5. Write the rule down

- [x] 5.1 `docs/manual/reference/compatibility.md`: a section stating the
      promise — additive within a major, a breaking change moves the path prefix
      **and** ships as a major — plus what the gate does and how the escape
      works. Note that `/api/**` is unversioned today and the prefix arrives with
      the follow-up change.
- [x] 5.2 `docs/manual/reference/api/index.md`: point its Conventions section at
      the promise.
- [x] 5.3 `CLAUDE.md`: one pointer line under Process (a pointer, not a copy).
- [x] 5.4 `.claude/skills/code-conventions/SKILL.md`: the regenerate rule next to
      the existing `openapi.json` → `types.gen.ts` lines — the committed document
      is verified by a test, so regenerate it in the same change.

## 6. Gates and close-out

- [x] 6.1 `./mvnw clean verify` (`clean` — the reqstool gate needs it).
- [x] 6.2 `(cd src/main/frontend && pnpm test:stories)` and `pnpm e2e`.
- [x] 6.3 `reqstool status local -p docs/reqstool` ends `PASS`.
- [x] 6.4 `openspec validate --all --strict` and `mkdocs build --strict`.
- [x] 6.5 Verify the gate on the PR itself: it must be green on this
      (non-breaking) change, and a scratch commit that deletes an endpoint must
      turn it red. Do not trust an unexercised gate.
- [x] 6.6 Write `openspec/changes/add-api-compatibility-gates/evidence.md` — the
      commands and pasted result tails of one fresh run after the last edit, plus
      the commit SHA.
- [ ] 6.7 Open the PR with an Evidence section; archive the change as the final
      commit (`/opsx:archive`).
