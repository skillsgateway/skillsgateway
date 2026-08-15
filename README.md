# Skills Gateway

An enterprise gateway for git-distributed AI agent skill marketplaces
(Claude Code plugins, GitHub Copilot, Cursor) — the missing analogue of
Artifactory/Nexus for the part of the skills ecosystem that never touches a
package manager.

Skill marketplaces are git repositories cloned straight from the public
internet: no vetting gate, no immutable versions, no inventory, no audit
trail. The Skills Gateway is the choke point: it ingests upstream
marketplaces into quarantine, holds every snapshot until it passes vetting,
and serves only approved, SHA-pinned content to unmodified git clients —
with every fetch in an append-only audit ledger.

**Status: pre-alpha.** The architecture and requirements are established;
the product implementation (Java 25 / Spring Boot 4 / JGit, GraalVM
native-image at release) is being scaffolded. A validated Python prototype
of the full loop lives in the sibling `skills-gateway-python-mvp` repository.

## Build and run locally

```bash
./mvnw verify                                # Java + portal gates, packaged jar (needs Docker)
(cd ui && pnpm e2e)                          # portal e2e: real browser + mock OIDC login
reqstool status local -p docs/reqstool       # traceability gate (after the two above)
./mvnw -Pnative -DskipTests native:compile          # GraalVM native binary (needs GraalVM CE 25)
docker build -t skills-gateway:local .       # OCI image from the native binary
docker compose up                            # gateway + PostgreSQL on :8080
```

The admin portal (React/Vite, `ui/`) is **built by the Maven build** — the
frontend-maven-plugin provisions node/pnpm, runs the UI gates, and packages the
bundle into the jar, served at `/` behind the OIDC login. You never need pnpm to
build or run the gateway. The `cd ui && pnpm …` commands exist only for UI
development loops (`pnpm dev` proxies `/api` to a running gateway, `pnpm test`,
`pnpm storybook`) and for the e2e suite (`pnpm e2e`), which is deliberately
outside `mvnw verify`.

Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md).

The API is documented with springdoc: `/v3/api-docs` (OpenAPI 3) and
`/swagger-ui.html` (UI), behind the OIDC login like the rest of the web
surface. Point the `SGW_OIDC_*` environment variables at your IdP to log in
locally; `/actuator/health` is available unauthenticated.

Kubernetes deployment: `helm/skills-gateway` (bring your own PostgreSQL and
OIDC provider — see `values.yaml`).

Dependency updates are automated with Renovate (`renovate.json`); enable the
Renovate GitHub App on the repository for it to take effect.

## Documentation

The user-facing documentation site (MkDocs Material, `docs/manual/`) is
published to <https://jimisola.github.io/skills-gateway>: background and goals,
concepts (the quarantine → approve → serve lifecycle, snapshots and the audit
ledger, trust boundaries), guides, and reference (configuration, REST API, git
façade, portal). Build it locally with:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

In the repository:

- `ARCHITECTURE.md` — the full architecture (threat model, connector-based
  vetting, two-repo promotion, git façade, observability, roadmap)
- `docs/decisions/` — ADRs (language, toolchain)
- `docs/reqstool/` — requirements and verification cases
  (reqstool-traceable, `GW_*` / `SVC_GW_*`)
- `openspec/` — spec-driven change workflow (OpenSpec)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
