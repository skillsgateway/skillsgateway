# Compatibility and allowlists

What the gateway accepts, what it rejects, and what it does not support yet.
Everything on this page is enforced in code, not merely recommended.

## URL schemes

Applied to every operator-supplied outbound URL. The scheme is lower-cased and
matched against `skills-gateway.allowed-url-schemes`.

| Scheme | Default | Notes |
| --- | --- | --- |
| `https` | **Allowed** | The intended production scheme. |
| `http` | **Allowed** | Present for local development and internal forges. Consider narrowing to `https` alone in production. |
| `ssh`, `git`, `git+ssh` | Rejected | Not in the default allowlist. The gateway clones over HTTP(S) with JGit. |
| `file` | Rejected | Would let a registration read the gateway's own filesystem. |
| `ext`, and any other scheme | Rejected | Not in the allowlist. |
| *(no scheme, or unparseable)* | Rejected | **The check fails closed** — an unparseable URL is a rejection, not a pass-through. |

Rejection is HTTP **400** with a `ProblemDetail` naming the allowed schemes.

!!! warning "Widening the allowlist widens a trust boundary"

    `allowed-url-schemes` governs what a registration can make the gateway
    connect to. Adding `file` or `ext` would let an operator-supplied string
    reach local resources. Treat changes to it as a security decision.

    An empty list is not configurable — it falls back to the default.

## Refs

| Input | Behaviour |
| --- | --- |
| `ref` absent | Accepted. The default branch is ingested. |
| `ref: main` | Accepted — identical to omitting it. |
| Any other ref | **400.** |

Which ref is ingested is the gateway's decision, not the registrant's. This is a
deliberate trust-boundary constraint rather than a missing feature: multi-ref
support will arrive as promotion per `(upstream, ref)`, with each ref advancing
independently through the same approval gate, not by relaxing this check.

Consequently `marketplace add <url>#release-1.x` against the facade will not
find a ref — only `main` exists on the published repository.

## Names

Marketplace names must match `^[a-z0-9][a-z0-9_-]*$`: lowercase letters, digits,
hyphen and underscore, not starting with a hyphen or underscore. A violation is
**422**.

The name is also a path segment on the facade (`/git/{name}`), which is why the
character set is constrained.

## Plugin sources inside a marketplace

The current scope accepts **local sources only**.

| Source type | Status |
| --- | --- |
| Relative path inside the marketplace repository | **Accepted** |
| `github`, `url`, `git-subdir`, `npm`, `archive`, or any external source | **Rejected fail-closed** at ingestion, with the reason recorded as the snapshot's violation |

This removes transitive resolution and source rewriting from the current scope
entirely: relative sources resolve inside the served snapshot by themselves, so
every URL a client dereferences already resolves inside the gateway.

Supporting external sources means mirroring the closure and rewriting the
manifest — a designed future capability, not a configuration flag.

## Clients

The facade is plain read-only git smart-HTTP, so anything that clones works.

| Client | Support |
| --- | --- |
| Claude Code (`claude plugin marketplace add`) | The primary target. |
| Copilot / Cursor (open Agent Skills `SKILL.md` repositories) | Works — the facade serves a git repository. |
| CI pipelines, bare `git clone` | Works with a PAT. |
| Anything attempting `git push` | Rejected by construction. |

## Enforcement mechanisms

Making the gateway the *only* path is outside the gateway itself:

| Mechanism | Availability |
| --- | --- |
| Claude Code `strictKnownMarketplaces` (managed settings) | Available today — a hard client-side allowlist. |
| Claude Code `extraKnownMarketplaces`, `enabledPlugins` | Available today — pre-register and force-install. |
| Copilot / Cursor equivalent | **None.** Egress policy carries the load. |
| Network egress blocking of upstream marketplace hosts | Your network, not the gateway. |

## Platform

| Component | Requirement |
| --- | --- |
| Runtime | Java 25 (Temurin); GraalVM CE 25 for native builds |
| Database | PostgreSQL |
| Identity | An OIDC provider supporting authorization-code flow |
| Container | Distroless image built from the native binary |
| Kubernetes | `helm/skills-gateway`; bring your own PostgreSQL and OIDC provider |
