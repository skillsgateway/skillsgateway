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
6. Open a PR; the PR title must be a Conventional Commit (it becomes the squash
   commit). PRs are merged manually by the maintainer.
7. After merge, the OpenSpec change (including its `evidence.md`) is archived
   into `openspec/specs/`.

## Gates — all must pass

```bash
./mvnw clean verify                     # Java + UI gates + packaged jar
(cd src/main/frontend && pnpm e2e)    # real-browser e2e (compose.e2e.yaml)
reqstool status local -p docs/reqstool  # must end "PASS"
openspec validate --all --strict
mkdocs build --strict                   # docs site (pip install -r docs/requirements.txt)
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
traceability), `.claude/skills/design-conventions/SKILL.md` (portal UI), and
`.claude/skills/documentation/SKILL.md` (docs structure, Markdown and Mermaid
conventions). Architecture context: `ARCHITECTURE.md` and `docs/decisions/`.

## Documentation

The published site is MkDocs Material, built from `docs/manual/` and configured
by `mkdocs.yml`. Rolling `dev` docs publish from `main`; `v*` tags publish a
versioned release behind the `stable` alias (mike).

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

## License

By contributing you agree that your contributions are licensed under the
Apache License 2.0 and you certify the [Developer Certificate of
Origin](https://developercertificate.org/).
