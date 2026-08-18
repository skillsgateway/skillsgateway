# Skills Gateway — agent instructions

Enterprise gateway for git-distributed AI agent skill marketplaces: quarantined
ingestion of upstream git repos into SHA-pinned snapshots, held-until-approved
vetting, a read-only git smart-HTTP facade serving only approved content, and an
append-only audit ledger. Java 25 / Spring Boot 4 backend, React/Vite portal in
`src/main/frontend/`, PostgreSQL, JGit (never subprocess git).

## Skills

Load these before working in their areas:

- `.claude/skills/architecture` — system model, threat model, where decisions live (ADRs)
- `.claude/skills/code-conventions` — build, gates, Java/TS conventions, traceability annotations
- `.claude/skills/design-conventions` — portal design system and UI verification rules
- `.claude/skills/documentation` — MkDocs site structure, Markdown/Mermaid conventions, docs-in-same-PR rule
- `.claude/skills/impeccable` — design harness (`/impeccable audit|harden|critique`) run on any PR touching a portal page; `PRODUCT.md` and `DESIGN.md` at the root are its context, and design-conventions outranks it
- `.claude/skills/old-coder` — evidence-first discipline for high-assurance work:
  changes touching trust boundaries (facade auth, `ApprovalService`, registration
  allowlist) or anything with data-loss/concurrency stakes. OpenSpec stays the
  approved spec of *what* to build; old-coder governs *how* the implementation
  earns trust (prove tests fail, gauntlet, evidence report).

## The gates (all must pass before any PR)

```bash
./mvnw clean verify                     # Java + UI gates + packaged jar (needs Docker)
(cd src/main/frontend && pnpm e2e)    # real-browser e2e vs mock OIDC IdP (needs Docker)
reqstool status local -p docs/reqstool  # requirements traceability — must end "PASS"
openspec validate --all --strict
mkdocs build --strict                   # docs site — pip install -r docs/requirements.txt
```

Use `clean` for the reqstool gate: incremental compilation truncates the
generated annotation files.

## Process

- Every behavior change starts as an OpenSpec change (`/opsx:propose`). A PR
  isn't done until its change is archived into `openspec/specs/`
  (`/opsx:archive`) as the final commit of the PR, after implementation and
  gates.
- Requirements live in `docs/reqstool/` (SSOT, `GW_*` / `SVC_GW_*`). Never state
  requirement text anywhere else; code carries `@Requirements`/`@SVCs`
  annotations (Java) or JSDoc tags (TypeScript).
- Never weaken or delete an existing SVC test to make a change pass.
- Documentation lives in `docs/manual/` (MkDocs Material). Any change to
  behavior, the REST API, configuration, or the portal updates the affected
  pages **in the same PR**.
- Evidence, not adjectives: every OpenSpec change gets an
  `openspec/changes/<name>/evidence.md` — the commands and pasted result tails
  of one final fresh run of all gates after the last code edit, plus the commit
  SHA. The PR body carries an **Evidence** section summarizing it. The report
  archives with the change.
- Risk-scaled enforcement: changes touching trust boundaries (facade auth,
  `ApprovalService`, registration allowlist) require adversarial/negative tests
  and the `.claude/skills/old-coder` discipline, not just happy-path coverage.
- Declarative estate configuration (#65) and IdP group-to-role mapping (#66)
  are continuous obligations, not one-shot features: any change that adds
  API-managed runtime state (a new estate object type, a new grantable role)
  must extend `skills-gateway.estate.*` — and keep group-mapping compatibility
  — **in the same PR**, or state in its design why the object is deliberately
  API-only (as PATs are).
- Conventional Commits with DCO sign-off (`git commit -s`); branches
  `<type>/<kebab-description>`; PR titles are conventional commits (squash).
- Work on branches, open PRs; merging is done manually by the owner.

## Boundaries

- The quarantine repo is never served; only `ApprovalService` publishes.
- The facade (`/git/**`) authenticates with PATs only; the web surface with
  OIDC only (`skills-gateway.dev-insecure-auth=true` is a local-dev escape
  hatch, never a default).
- Registration is the trust boundary: URL scheme allowlist, gateway-pinned ref.
