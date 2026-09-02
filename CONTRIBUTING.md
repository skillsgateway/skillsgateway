# Contributing to Skills Gateway

## Prerequisites

- JDK 25 (Temurin; GraalVM CE 25 only for native builds)
- A container runtime (Testcontainers/Arconia dev services, e2e infrastructure).
  Docker works out of the box; Podman needs setup — Ryuk cannot start rootless
  and the Floci dev service needs the in-VM socket path. Both present as a wall
  of test errors rather than as an environment problem; see
  [Running the gates on Podman](docs/manual/guides/local-development.md#running-the-gates-on-podman)
- `git`, `gh`
- reqstool CLI (`pipx install reqstool` — see `.github/workflows/ci.yml` for the
  currently pinned version) and OpenSpec CLI (`npm i -g @fission-ai/openspec`)
- Node/pnpm are provisioned by the Maven build; for UI development enable
  corepack (`corepack enable pnpm`)
- The vendored Impeccable design skill needs its own detector dependencies:
  `npm --prefix .claude/skills/impeccable install`. Without them the detector
  silently falls back to regex matching — computed contrast, CSS custom
  properties and selector matching are not evaluated, so a clean report and a
  broken detector look identical. A `SessionStart` hook warns when they are
  missing; `node .claude/scripts/check-impeccable-detector.mjs --fix` installs
  them.

## Workflow

1. Every behavior change starts as an OpenSpec change (`/opsx:propose` with
   Claude Code, or follow `openspec/` conventions manually): proposal, delta
   specs, design, tasks.
2. New behavior gets a requirement + verification case in `docs/reqstool/`
   (`GW_*` / `SVC_GW_*`) and traceability annotations in the code and tests.
3. Implement on a branch named `<type>/<kebab-description>`.
   Any change to behavior, the REST API, configuration, or the portal updates
   the affected pages under `docs/manual/` **in the same PR** — see
   `.claude/skills/documentation/SKILL.md`.
4. Run the gates (below) one final time after the last code edit and record the
   evidence: `openspec/changes/<name>/evidence.md` with each gate's command, the
   pasted tail of its actual output (numbers, not adjectives), and the commit
   SHA it was run against. The PR body carries an **Evidence** section
   summarizing it — the review target is the evidence, not every diff line.
5. Changes touching trust boundaries (facade auth, `ApprovalService`, the
   registration allowlist) are held to a higher bar: adversarial/negative tests
   are required, per `.claude/skills/old-coder/SKILL.md`.
6. Archive the OpenSpec change (including its `evidence.md`) into
   `openspec/specs/` as the final commit of the PR.
7. Open (or finalize) the PR; the PR title must be a Conventional Commit (it
   becomes the squash commit). The PR contains the implementation, docs,
   evidence, and the archived change. PRs are merged manually by the
   maintainer.

## Repository settings and labels

Repository settings are not configured by clicking through the GitHub UI, and
they are **not** in this repo. They live as code under `safe-settings/` in
`skillsgateway/.github` and are applied by
[`github/safe-settings`](https://github.com/github/safe-settings) — merge
methods, the label set, the `protect-main` ruleset, and the deployment
environments. This repo previously carried `.github/settings.yml` for the same
purpose; it was retired in favour of that single source (issue #23).

Two consequences worth knowing before you open a PR:

- **`main` is protected.** A PR needs the required checks (`Build & gates`,
  `Storybook tests`, `Portal e2e`, `Traceability & spec gates`,
  `Documentation (strict)`, `Breaking change detection`,
  `semantic-pr / Validate PR title`) green, one approving review, and all review
  threads resolved. Force-pushes and branch deletion are blocked.
- **Labels are declarative.** `.github/labeler.yml` may only reference labels
  declared in the admin repo; a label that exists on the repo but not there is
  deleted on the next sync. Adding a label means a PR against that repo. CI
  checks that every glob in that file still matches a tracked path
  (`.github/verify-labeler.py`), because one that matches nothing applies no
  label and reports nothing.
- **`⚠️ BREAKING CONTRACT` is applied by hand.** It is the API contract
  workflow's escape hatch, so no glob in `.github/labeler.yml` applies it — a
  person does, deliberately. Like every other label it has to be declared in the
  admin repo, or the next sync deletes it and the escape hatch becomes
  unreachable.

## Gates — all must pass

```bash
./mvnw clean verify                     # Java + UI gates + packaged jar
(cd src/main/frontend && pnpm test:stories)  # Storybook story tests in real chromium
(cd src/main/frontend && pnpm e2e)    # real-browser e2e (compose.e2e.yaml)
reqstool status local -p docs/reqstool  # must end "PASS"
openspec validate --all --strict
mkdocs build --strict                   # docs site (pip install -r docs/requirements.txt)
```

CI enforces the same gates per PR (`.github/workflows/ci.yml`) plus a native
image build on main (`native.yml`). For speed, CI splits them across parallel
jobs: build + unit gates, the Storybook story tests, and the portal e2e run
concurrently (the e2e job packages its own jar with `-DskipTests
-Dskip.ui.verify=true`; the story tests get their own runner because a real
chromium starves when it shares two cores with the Java build, #103), and a
final job joins the gates and e2e jobs' junit results for the reqstool and
OpenSpec gates.

`clean` matters for the reqstool gate: incremental compilation truncates the
generated annotation files. The list is an ordered sequence, not a set:
`reqstool status` reads the portal suites' JUnit output from
`src/main/frontend/test-results/`, so it must run after `pnpm e2e`. It also
**exits 0 even when it prints FAIL** — read its last line rather than its exit
code.

## Building and running the packaged artifacts

```bash
./mvnw -Pnative -DskipTests native:compile   # GraalVM native binary (needs GraalVM CE 25)
docker build -t skills-gateway:local .       # OCI image from the native binary
docker compose up                            # gateway + PostgreSQL on :8080 (compose.yaml)
```

The admin portal (React/Vite, `src/main/frontend/`) is **built by the Maven
build** — the frontend-maven-plugin provisions node/pnpm, runs the UI gates,
and packages the bundle into the jar, served at `/` behind the OIDC login. You
never need pnpm to build or run the gateway. The `cd src/main/frontend &&
pnpm …` commands exist only for UI development loops (`pnpm dev` proxies
`/api` to a running gateway, `pnpm test`, `pnpm storybook`) and for the two
real-browser suites — Storybook story tests (`pnpm test:stories`) and e2e
(`pnpm e2e`) — which are deliberately outside `mvnw verify`.

## Local observability and dev services

Container-backed dependencies come from **Arconia Dev Services**, so one
container serves both `bootRun` and the tests rather than each being configured
separately. Two are wired up:

| Dev service | Starts |
| --- | --- |
| `arconia-dev-services-postgresql` | **Automatically**, in dev and test. Nothing to enable. |
| `arconia-dev-services-lgtm` | **Only** under the `observability` Spring profile. |

The LGTM stack (OpenTelemetry Collector, Loki, Tempo, Prometheus, Grafana) is
opt-in because it is heavy and unnecessary for ordinary work:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=observability \
                       -Dspring-boot.run.useTestClasspath=true
```

`useTestClasspath` is required, not optional: the dev-service dependencies are
test-scoped, so the profile finds nothing without it — deliberately, so nothing
observability-related is reachable from the packaged jar, the container image or
the native binary. The Grafana URL is logged at startup. Configuration lives in
`src/main/resources/application-observability.yaml`, including a 5-minute startup
timeout because the image boots six processes and the default is tight on a small
container VM.

Metrics are always recorded; only the *export* is opt-in. In a deployment it is
enabled with `arconia.otel.enabled=true` plus the standard `OTEL_EXPORTER_OTLP_*`
variables — see `docs/manual/reference/observability.md`.

When adding a container-backed dependency, check the arconia BOM for an existing
dev service before hand-rolling a Testcontainers container; the published list is
longer than a Maven Central name search suggests.

Dependency updates are automated with Renovate (`renovate.json`); enable the
Renovate GitHub App on the repository for it to take effect.

## Commits

- [Conventional Commits](https://www.conventionalcommits.org/):
  `<type>(<scope>): <description>` with types
  `feat|fix|build|chore|ci|docs|perf|refactor|revert|style|test`.
- **DCO sign-off required**: commit with `git commit -s`
  (`Signed-off-by: Your Name <you@example.com>`).
- Format Java before committing: `./mvnw spotless:apply`.

## Code conventions

See `.claude/skills/code-conventions/SKILL.md` (build, Java/TS style,
traceability), `.claude/skills/design-conventions/SKILL.md` (portal UI), and
`.claude/skills/documentation/SKILL.md` (docs structure, Markdown and Mermaid
conventions). Architecture context: `docs/manual/architecture.md` and
`docs/decisions/`.

## Documentation

The published site is MkDocs Material, built from `docs/manual/` and configured
by `mkdocs.yml`. Rolling `dev` docs publish from `main`; a release publishes a versioned
release behind the `stable` alias (mike). Release tags are bare semantic
versions with no `v` prefix, and publishing runs from the gated release
workflow rather than from a tag push — see
`docs/manual/guides/releasing.md`.

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

## License

By contributing you agree that your contributions are licensed under the
Apache License 2.0 and you certify the [Developer Certificate of
Origin](https://developercertificate.org/).
