# Skills Gateway

An enterprise gateway for git-distributed AI agent skill marketplaces
(Claude Code plugins, GitHub Copilot, Cursor) — the missing artifact
repository manager for the part of the skills ecosystem that never touches
a package manager.

Skill marketplaces are git repositories cloned straight from the public
internet: no vetting gate, no immutable versions, no inventory, no audit
trail. The Skills Gateway is the choke point: it ingests upstream
marketplaces into quarantine, holds every snapshot until it passes vetting,
and serves only approved, SHA-pinned content to unmodified git clients —
with every fetch in an append-only audit ledger.

**Status: pre-1.0.** The core loop is implemented on Java 25 / Spring Boot 4 /
JGit (GraalVM native-image at release): ingestion into quarantine, hold until
approved, and serving approved SHA-pinned content through the PAT-authenticated
git smart-HTTP facade, with an admin portal and an append-only audit ledger.
Lifecycle webhooks, ledger export, and snapshot retention build on that. There
are no releases or tags yet, and interfaces may still change.

## What it does

- **Quarantined ingestion.** An upstream marketplace is cloned into a
  quarantine repository the facade never serves, pinned as a snapshot at an
  immutable SHA.
- **Hold until approved.** Nothing reaches a client until a reviewer decides.
  Approval publishes exactly that SHA; upstream movement alone never propagates.
- **A read-only git facade.** `/git/**` is an ordinary git remote for
  unmodified clients, authenticated with personal access tokens and serving only
  approved content.
- **An append-only audit ledger.** Every fetch and every administrative action
  is recorded with the acting identity, the marketplace and the SHA.
- **Lifecycle webhooks.** Signed, retried delivery of snapshot ingest, approve,
  reject, soft-delete and restore events to registered subscribers.
- **Ledger export.** The ledger as a compliance feed — an NDJSON stream with a
  resume cursor, or push sinks that reuse the signed webhook delivery path.
- **Snapshot retention.** Opt-in policies that reclaim quarantine storage on
  stated criteria, with a restore window, and never touching served content.
- **An admin portal.** React/Vite, bundled into the jar, behind OIDC login.

## Run it

Runtime requirements: **PostgreSQL 18** (every provisioning path in the repo —
compose, e2e infrastructure, CI, and the Arconia/Testcontainers dev services —
runs 18) and an **OIDC identity provider** for the web surface. `compose.yaml`
brings its own PostgreSQL; the Helm chart expects you to bring both.

```bash
./mvnw -Pnative -DskipTests native:compile   # GraalVM native binary (needs GraalVM CE 25)
docker build -t skills-gateway:local .       # OCI image from the native binary
docker compose up                            # gateway + PostgreSQL on :8080 (compose.yaml)
```

The API is documented with springdoc: `/v3/api-docs` (OpenAPI 3), rendered by
the bundled Scalar UI at `/docs`, behind the OIDC login like the rest of the web
surface. Point the `SGW_OIDC_*` environment variables at your IdP to log in
locally; `/actuator/health` is available unauthenticated.

Kubernetes deployment: `helm/skills-gateway` (bring your own PostgreSQL and
OIDC provider — see `values.yaml`).

## Contributing

Everything about building from source, the five quality gates, and the
evidence-first workflow lives in [CONTRIBUTING.md](CONTRIBUTING.md): each
OpenSpec change records its gate runs in an `evidence.md`, and the PR body
carries an **Evidence** section, so the review target is the evidence rather
than every diff line.

## Documentation

The user-facing documentation site (MkDocs Material, `docs/manual/`) is
published to <https://skillsgateway.github.io/skillsgateway>: background and goals,
concepts (the quarantine → approve → serve lifecycle, snapshots and the audit
ledger, trust boundaries), guides (local development, registering a marketplace,
approving snapshots, consuming skills, webhooks, ledger export, retention), and
reference (configuration, REST API, git façade, retention, portal). Build it
locally with:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

In the repository:

- `docs/manual/architecture.md` — the full architecture (threat model,
  connector-based vetting, two-repo promotion, git façade, observability,
  roadmap), published as part of the docs site
- `docs/decisions/` — ADRs (language, toolchain, frontend stack and its
  verification harness)
- `docs/reqstool/` — requirements and verification cases
  (reqstool-traceable, `GW_*` / `SVC_GW_*`)
- `openspec/` — spec-driven change workflow (OpenSpec)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
