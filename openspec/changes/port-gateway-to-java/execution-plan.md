# Execution plan: autonomous apply of port-gateway-to-java

Governs the unattended `/opsx:apply` run. tasks.md defines *what*; this
defines *how the agent operates* while the user is away.

## Objective and definition of done

All 30 tasks in tasks.md checked off, and every gate below green:

1. `./mvnw verify` passes (compile, tests, Spotless, Checkstyle).
2. `reqstool status local -p docs/reqstool` reports **14/14 complete · PASS**.
3. `openspec validate --all --strict` passes.
4. Work committed in logical units (Conventional Commits, DCO `-s`,
   session trailer) on the work branch — never on `main`.
5. A final report: what was built, gate results, deviations, open items.

## Branch strategy

Create `feat/port-gateway-to-java` from `feat/scaffold`. All implementation
commits land there. No remote exists; nothing is pushed. No merges — merging
is the user's (manual) step.

## Execution order and commit cadence

Follow tasks.md groups 1 → 11 in order (they are dependency-ordered).
Commit at group granularity (or a coherent pair of small groups); update
tasks.md checkboxes in the same commit as the work they describe. Run
`./mvnw verify` before every commit — a group is not done with a red build.

## Autonomy boundaries

**Decide alone (no recording needed):** dependency/pin versions, test
fixtures and helpers, Flyway column details within the designed schema,
package-internal naming, config property names, error-response shapes,
reflection/native metadata entries, test tactics (e.g. how to stub OIDC).

**Decide and record (amend design.md or the relevant ADR in the same
commit):** any deviation from a documented design decision — e.g. a
different publication mechanism than in-process fetch, a change to the
PostUploadHook audit shape, an extra dependency with architectural weight.
Precedent: the Python MVP's dulwich → subprocess-git revision.

**Stop and report (do not proceed on that item):**
- Anything that would invalidate an ADR decision (e.g. JGit fundamentally
  unworkable with Spring Boot 4 → the Quarkus fallback question is the
  user's, not the agent's).
- Anything requiring a new requirement (scope change): record the need,
  skip, continue with the rest.
- Destructive operations outside the work branch and repo.

## Failure policies

- **Native spike (task 11.1) fails:** record the failure and evidence in
  ADR 0002's risk clause, leave the task unchecked with a note, continue —
  the JVM path remains fully valid; framework switching is a user decision.
- **A test cannot pass after 3 distinct diagnostic attempts:** leave the
  task unchecked, write findings into the final report, continue with
  independent tasks. Never delete or weaken an SVC test to make a gate
  green; never update the reqstool SSOT to match a broken implementation.
- **Upstream/library surprises** (e.g. Spring Boot 4 API drift vs training
  data): verify against current docs/source (context7/deepwiki/web) before
  working around; prefer the documented current API.
- **Flaky test suspected:** rerun twice; if genuinely flaky, fix the test's
  determinism — flakiness is a bug, not noise.

## Verification gates (in order, all mandatory at the end)

| Gate | Command | Expectation |
|---|---|---|
| Build + quality | `./mvnw verify` | green incl. Spotless/Checkstyle |
| E2E reality | (part of verify) | real `git` binary clones through PAT auth |
| Traceability | `reqstool status local -p docs/reqstool` | 14/14 PASS |
| Spec validity | `openspec validate --all --strict` | pass |
| SBOM | test hits `/actuator/sbom` | CycloneDX doc served |

## Reporting

- Brief progress note at each group commit (what landed, gate status).
- Final report leads with gate results, then deviations
  (decide-and-record items), unchecked tasks with reasons, and the
  suggested next steps (archive change via /opsx:archive, portal change,
  CI when a remote exists).
- Task-tracker entries mirror the group structure for visibility.

## Explicitly out of scope for the autonomous run

Pushing to any remote, creating PRs, merging, archiving the OpenSpec
change, modifying `main`, starting the portal, changing ADR decisions.
