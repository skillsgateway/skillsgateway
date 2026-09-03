# Compatibility and allowlists

What the gateway accepts, what it rejects, what it does not support yet, and
what its own API promises in return. Everything on this page is enforced in code
or in CI, not merely recommended — where a rule is *not* mechanically enforced,
it says so.

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

An unconfigured gateway accepts **local sources only**, and that is the default.

| Source type | Status |
| --- | --- |
| Relative path inside the marketplace repository | **Accepted** |
| `github` | **Rejected fail-closed** unless `skills-gateway.ingestion.external-sources.enabled` is set; when it is, resolved and rewritten — see below |
| `git`/`url`, `git-subdir` | **Rejected fail-closed**. Not in the shipped `allowed-types`, and nothing resolves them yet |
| `npm`, `archive` | **Rejected fail-closed**, permanently: no configuration admits them |
| A source declaring a `ref` or a `sha` | **Rejected fail-closed**, naming the field. This gateway resolves at the remote's default branch head, and resolving a pinned source somewhere else would serve a commit the manifest did not name |
| Anything else — an unrecognised type, an object with no type, a value that is neither a path nor an object | **Rejected fail-closed**, with the form named in the snapshot's violation |

A relative source resolves inside the served snapshot by itself, so for a
local-only marketplace every URL a client dereferences already resolves inside
the gateway.

External source support arrives in increments
([ADR 0011](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0011-external-plugin-sources.md)).
An enabled gateway now **resolves** an admitted `github` source: it fetches the
repository into quarantine, grafts it under `_plugins/<plugin name>/`, and
rewrites the served manifest so that plugin's `source` is
`./_plugins/<plugin name>`. The snapshot is that composite commit, parented on
the upstream commit. So the property above holds for an enabled gateway too —
every URL a client dereferences resolves inside the gateway.

The invariant that governs both cases: a snapshot is held, and therefore
approvable, only when every source it declares resolves inside the snapshot the
gateway serves. Anything that stops a source from resolving — an unreachable
repository, a refused address or redirect, a breached budget, an exhausted
deadline, a graft that cannot be made — is a rejected snapshot recorded against
the upstream commit, never a held one.

Three further refusals belong to the graft rather than to the source type, and
each is fail-closed with no partial result: a marketplace repository that already
has a top-level `_plugins`; an external plugin whose name is not
`^[a-z0-9][a-z0-9_-]*$`; and two external plugins sharing a name.
## Clients

The facade is plain read-only git smart-HTTP, so anything that clones works.

| Client | Support |
| --- | --- |
| Claude Code (`claude plugin marketplace add`) | The primary target. |
| Copilot / Cursor (open Agent Skills `SKILL.md` repositories) | Works — the facade serves a git repository. |
| CI pipelines, bare `git clone` | Works with a PAT. |
| Anything attempting `git push` to `/git/**` | Rejected by construction. (A hosted marketplace is published to on `/publish/**`, a separate endpoint.) |

## Enforcement mechanisms

Making the gateway the *only* path is outside the gateway itself:

| Mechanism | Availability |
| --- | --- |
| Claude Code `strictKnownMarketplaces` (managed settings) | Available today — a hard client-side allowlist. |
| Claude Code `extraKnownMarketplaces`, `enabledPlugins` | Available today — pre-register and force-install. |
| Copilot / Cursor equivalent | **None.** Egress policy carries the load. |
| Network egress blocking of upstream marketplace hosts | Your network, not the gateway. |

## The API contract

Everything above is about what the gateway *accepts*. This is about what its own
HTTP surface *promises*.

!!! note "What this promise does not cover"

    It covers `/api/**`. It does **not** currently extend to the
    `skills-gateway.*` configuration surface, the Helm chart's values, the
    declarative estate schema, or the
    [lifecycle webhook payloads](../guides/lifecycle-webhooks.md) — those appear
    nowhere in the OpenAPI document, so the diff below cannot see them, however
    hard a renamed field there breaks a receiver. Whether those should carry the
    same additive obligation — and what would gate it — is open in
    [#121](https://github.com/skillsgateway/skillsgateway/issues/121).

    One consequence is live today: `skills-gateway.roles.enabled` was removed,
    and a deployment that still sets it is **refused at startup** rather than
    having the setting ignored. Ignoring it would reverse an operator who set it
    to `false`, and a property that is read and a property that is ignored look
    identical from inside a deployment. That refusal is a migration aid and is
    scheduled for removal at the next major.

**Within a major, `/api/**` only grows.** Endpoints, fields and enum values may
be added; nothing a deployed client could depend on is removed, narrowed or
renamed. A breaking change is allowed — it is not free. It moves the path prefix
**and** ships as a major release, so a client that pinned neither is never
surprised.

| Change | Additive? |
| --- | --- |
| A new endpoint, a new optional field, a new enum value | **Yes.** Ships as a minor. |
| `/api/v2/...` added while `/api/v1/...` remains | **Yes** — nothing was taken away. Deprecate first, remove in a later major. |
| Removing or renaming an endpoint or field, making an optional field required, narrowing a type | **No.** New prefix, and a major. |

!!! note "The prefix does not exist yet"

    Endpoints are unversioned today (`/api/marketplaces`, not
    `/api/v1/marketplaces`). The version segment arrives with the change that
    introduces API versioning; until then the promise binds, and the check below
    enforces it, against the unversioned paths.

### How it is enforced

Three mechanisms, and it is worth being clear about which is which.

| Rule | Enforced by |
| --- | --- |
| The published contract is the contract the gateway serves | A build test. `src/main/frontend/openapi.json` must equal the document the running application serves, or `./mvnw verify` fails naming the command that regenerates it. |
| A breaking change is detected | The **API contract** workflow diffs every pull request's contract against the one it forked from with [oasdiff](https://github.com/oasdiff/oasdiff), and fails on a breaking classification. |
| A breaking change is declared | The same workflow requires the PR title — which becomes the squash commit subject, and so the release version — to carry `!` or `BREAKING CHANGE`. |
| The prefix *was* moved | **Nobody.** No check can tell that a break should have been versioned instead; that is review's job. |

Two of oasdiff's default severities are raised to errors in `.oasdiff.yaml`:
removing a response field, and removing an optional response header. Both are
warnings by default unless the schema marks them required, and no response
schema here does — so the most ordinary breaking change there is used to pass a
gate that fails on errors only.

Deliberately breaking the contract takes two visible acts: the
`⚠️ BREAKING CONTRACT` label on the pull request, and the break declared in its
title. Both stay in the record. Keeping the old surface alongside the new one
needs neither — that path is additive, and the gate passes on its own.

The document served at `/v3/api-docs` declares the release it describes, derived
from the build. The copy published in the repository carries a placeholder there
instead: it is regenerated by hand, and a version that changes on every commit
would make it differ from the build on every commit.

## Platform

| Component | Requirement |
| --- | --- |
| Runtime | Java 25 (Temurin); GraalVM CE 25 for native builds |
| Database | PostgreSQL |
| Identity | An OIDC provider supporting authorization-code flow |
| Container | Distroless image built from the native binary |
| Kubernetes | `helm/skills-gateway`; bring your own PostgreSQL and OIDC provider |
