# Proposal: upstream-github-app

## Why

#498 lets the gateway read a private upstream with a static token configured for
its URL prefix. On GitHub that token is a personal access token: it belongs to a
person, it outlives the person's role, it is scoped to one owner at best, and it
is rotated by hand. #494 parked the alternative, a **GitHub App**, as "later".
An App is owned by the organisation, installed on the repositories it may read,
and hands out installation tokens that expire after an hour. This change adds it
as a second kind of credential under the same prefix matching.

## What Changes

- **A second credential kind.** An entry of
  `skills-gateway.ingestion.upstream-credentials` is either the existing
  `username`/`token` pair or a `github-app` block:
  - `app-id` (required);
  - `private-key` (required), the PEM, supplied by `${ENV_VAR}` reference
    exactly as the static token is. PKCS#1 (`BEGIN RSA PRIVATE KEY`, what GitHub
    issues) and PKCS#8 are both accepted;
  - `installation-id` (optional). When omitted, the gateway asks the API which
    installation covers the repository;
  - `api-url` (optional, default `https://api.github.com`). GitHub Enterprise
    Server sets its own.
- **Per fetch** (registration's reachability check and every ingestion or
  sync), the gateway signs a short-lived app JWT with the key and exchanges it
  for an installation token **scoped to the one repository being read**, with
  `contents: read` only. The token rides on #498's connection factory as Basic
  `x-access-token:<token>`, so #498's guarantees hold unchanged: only under the
  prefix, never across a redirect, JGit's own `Authorization` dropped.
- **Tokens are cached** per entry and repository until five minutes before they
  expire. A git refusal with a cached token drops it and mints once more; a
  second refusal is reported, not retried.
- **Failures read as operator instructions**, through #497's `UpstreamFailure`:
  the App is not installed on the repository, the installation is suspended or
  gone, the key was refused, the clocks disagree, the API is unreachable, the
  URL does not name a repository.
- **The API base comes only from configuration.** Nothing in a marketplace URL
  chooses where the JWT goes. The owner and repository taken from the URL must
  be plain names before they reach an API path.
- **A bad entry stops startup**: a malformed key, an unresolved `${…}`, a
  missing or non-numeric `app-id`, a non-https `api-url` (http only to
  loopback, as for prefixes), or both kinds on one entry.
- **Nothing repeats the key, the JWT or an installation token**: logs, the
  ledger, `fetch_log`, errors and API responses. The `marketplace-registered`
  ledger entry adds the kind, `credential=<prefix> (github-app)`.
- The forge REST metadata lookup and external plugin sources stay anonymous, as
  #498 decided.

## Why the surface grows

The operator-visible surface grows by one optional block of four keys inside
each element of `ingestion.upstream-credentials`.

- **`ConfigSurfaceBudgetTests` does not move.** It counts a list as one leaf
  and does not descend into the element type, so `BUDGET` stays at 109. The
  growth is argued here regardless, because the ratchet's question is the same.
- **No existing leaf can express it.** A static token cannot be a GitHub App:
  the App's credential is a signing key and two identifiers, and the token is
  minted from them. Reusing `username`/`token` to smuggle a key and ids through
  would make one field mean three things.
- **The capability map absorbs it.** It is the same *Registration* and
  *Ingestion* capability as #498, the same list, and the same trust boundary. No
  area, backend package, estate object type, role or sweep is added. The four
  keys are the minimum GitHub's API needs (`installation-id` and `api-url` are
  optional and default sensibly).
- **`ContextBudgetTests` does not move.** The integration suite adds its entries
  to `AbstractExternalSourceTest`'s existing context.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds
  - GW_INGEST_0056 — A private upstream is read with a GitHub App installation token
  - GW_INGEST_0057 — A GitHub App installation token is scoped to the one repository being read
  - GW_INGEST_0058 — An installation token is reused until shortly before it expires and renewed once after a refusal
  - GW_INGEST_0059 — A GitHub App token failure says why in an operator's words
  - GW_INGEST_0060 — The gateway refuses to start with an unusable GitHub App credential
  - GW_INGEST_0061 — The GitHub API a token is minted at comes only from configuration
  - GW_INGEST_0062 — The registration ledger entry names the credential kind
- `auth`: adds
  - GW_AUTH_0049 — A GitHub App key, assertion or installation token is never repeated by the gateway
  - GW_AUTH_0050 — A GitHub App assertion is short-lived
  - GW_AUTH_0051 — A GitHub App assertion is sent only to the configured API

## Impact

- **Server:** `ingestion/GitHubAppTokens` (new: key loading, JWT, token exchange,
  cache, failure translation); `UpstreamCredentials` validates the new kind and
  hands out a per-fetch header; `UpstreamGit` wraps each upstream operation in
  one retry on a refused cached token; `UpstreamFailure` gains the App reasons;
  `SkillsGatewayProperties.UpstreamCredential` gains `githubApp`.
- **Dependencies:** none new. The JWT is signed with nimbus-jose-jwt, already
  on the classpath through Spring Security's OAuth2 support.
- **Schema, API contract, portal, Helm chart:** unchanged.
- **Tests:** `GitHttpFixture` can play GitHub's REST API (status, body, and a
  record of every request's method, path, `Authorization` and body). The RSA
  key is generated at test time.
- **Docs:** the private-upstreams guide gains a GitHub App section (why, how to
  create one, local/Helm/ECS); the configuration reference, trust boundaries
  and the capability map row are updated.
