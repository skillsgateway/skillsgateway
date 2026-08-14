# Contributing to Skills Gateway

## Prerequisites

- JDK 25 (Temurin; GraalVM CE 25 only for native builds)
- Docker (Testcontainers/Arconia dev services, e2e infrastructure)
- `git`, `gh`
- reqstool CLI (`pipx install reqstool` — see `.github/workflows/ci.yml` for the
  currently pinned version) and OpenSpec CLI (`npm i -g @fission-ai/openspec`)
- Node/pnpm are provisioned by the Maven build; for UI development enable
  corepack (`corepack enable pnpm`)

## Workflow

1. Every behavior change starts as an OpenSpec change (`/opsx:propose` with
   Claude Code, or follow `openspec/` conventions manually): proposal, delta
   specs, design, tasks.
2. New behavior gets a requirement + verification case in `docs/reqstool/`
   (`GW_*` / `SVC_GW_*`) and traceability annotations in the code and tests.
3. Implement on a branch named `<type>/<kebab-description>`.
4. Run the gates (below), open a PR; the PR title must be a Conventional Commit
   (it becomes the squash commit). PRs are merged manually by the maintainer.
5. After merge, the OpenSpec change is archived into `openspec/specs/`.

## Gates — all must pass

```bash
./mvnw clean verify                     # Java + UI gates + packaged jar
(cd ui && pnpm e2e)                     # real-browser e2e (compose.e2e.yaml)
reqstool status local -p docs/reqstool  # must end "PASS"
openspec validate --all --strict
```

CI enforces the same gates per PR (`.github/workflows/ci.yml`) plus a native
image build on main (`native.yml`).

## Commits

- [Conventional Commits](https://www.conventionalcommits.org/):
  `<type>(<scope>): <description>` with types
  `feat|fix|build|chore|ci|docs|perf|refactor|revert|style|test`.
- **DCO sign-off required**: commit with `git commit -s`
  (`Signed-off-by: Your Name <you@example.com>`).
- Format Java before committing: `./mvnw spotless:apply`.

## Code conventions

See `.claude/skills/code-conventions/SKILL.md` (build, Java/TS style,
traceability) and `.claude/skills/design-conventions/SKILL.md` (portal UI).
Architecture context: `ARCHITECTURE.md` and `docs/decisions/`.

## License

By contributing you agree that your contributions are licensed under the
Apache License 2.0 and you certify the [Developer Certificate of
Origin](https://developercertificate.org/).
