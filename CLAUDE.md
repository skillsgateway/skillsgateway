# Skills Gateway — agent instructions

Enterprise gateway for git-distributed AI agent skill marketplaces: quarantined
ingestion of upstream git repos into SHA-pinned snapshots, held-until-approved
vetting, a read-only git smart-HTTP facade serving only approved content, and an
append-only audit ledger. Java 25 / Spring Boot 4 backend, React/Vite portal in
`ui/`, PostgreSQL, JGit (never subprocess git).

## Skills

Load these before working in their areas:

- `.claude/skills/architecture` — system model, threat model, where decisions live (ADRs)
- `.claude/skills/code-conventions` — build, gates, Java/TS conventions, traceability annotations
- `.claude/skills/design-conventions` — portal design system and UI verification rules

## The gates (all must pass before any PR)

```bash
./mvnw clean verify                     # Java + UI gates + packaged jar (needs Docker)
(cd ui && pnpm e2e)                     # real-browser e2e vs mock OIDC IdP (needs Docker)
reqstool status local -p docs/reqstool  # requirements traceability — must end "PASS"
openspec validate --all --strict
```

Use `clean` for the reqstool gate: incremental compilation truncates the
generated annotation files.

## Process

- Every behavior change starts as an OpenSpec change (`/opsx:propose`) and ends
  archived into `openspec/specs/` (`/opsx:archive`).
- Requirements live in `docs/reqstool/` (SSOT, `GW_*` / `SVC_GW_*`). Never state
  requirement text anywhere else; code carries `@Requirements`/`@SVCs`
  annotations (Java) or JSDoc tags (TypeScript).
- Never weaken or delete an existing SVC test to make a change pass.
- Conventional Commits with DCO sign-off (`git commit -s`); branches
  `<type>/<kebab-description>`; PR titles are conventional commits (squash).
- Work on branches, open PRs; merging is done manually by the owner.

## Boundaries

- The quarantine repo is never served; only `ApprovalService` publishes.
- The facade (`/git/**`) authenticates with PATs only; the web surface with
  OIDC only (`skills-gateway.dev-insecure-auth=true` is a local-dev escape
  hatch, never a default).
- Registration is the trust boundary: URL scheme allowlist, gateway-pinned ref.
