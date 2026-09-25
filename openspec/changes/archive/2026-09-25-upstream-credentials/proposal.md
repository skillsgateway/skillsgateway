# Proposal: upstream-credentials

## Why

A trial deployment of `0.3.0-b1` could not ingest private or internal upstream
repositories (#494). Every upstream fetch is anonymous, so a private repository
answers "repository not found or requires authentication" and there is no way
to give the gateway read access. #497 put ingestion and the registration
reachability check on one connection path, `UpstreamGit`, with a seam where
credentials attach. This change fills that seam.

## What Changes

- **Upstream credentials, matched by URL prefix.** A new list,
  `skills-gateway.ingestion.upstream-credentials`. Each entry has a
  `url-prefix`, a `username` and a `token`.
  - The token arrives by environment-variable reference (`${SGW_...}`), like
    every other secret the gateway reads. It is never stored in the database.
  - A marketplace URL uses the entry with the longest matching prefix. Prefixes
    match on whole path segments, so `https://github.com/acme` never matches
    `https://github.com/acme-evil/...`.
  - Registration's reachability check and every ingestion fetch use the same
    credential, through `UpstreamGit`.
- **A credential goes only to its own prefix.**
  - It is attached per HTTP request, and only when that request's URL is under
    the prefix the marketplace URL matched.
  - A redirect to another host, port, scheme or path outside that prefix gets
    no credential. A redirect never switches to another entry's credential.
  - A request URL with dot segments or encoded separators gets no credential.
- **A credential is never repeated.** It is kept out of logs, the ledger,
  `fetch_log`, the marketplace record, error messages and API responses,
  including anything JGit quotes. `toString` on the properties omits it.
- **A bad entry stops startup.** The gateway refuses to start when an entry
  has:
  - an unresolved `${...}` reference, or a blank token or username;
  - a prefix that is not `https`, except `http` to a loopback host;
  - userinfo, a query or a fragment in the prefix;
  - a prefix that another entry also declares.
- **Registration refuses userinfo in a clone URL.** A URL that embeds a
  credential would store it in the marketplace record and echo it in every
  API response. Credentials now have a proper home.
- **The ledger names the prefix.** The `marketplace-registered` entry records
  which credential prefix, if any, the upstream was read with. It never records
  the token.
- **The token's scope is the limit.** Registration stays admin-only and
  unchanged. The docs say plainly that registering under a credentialed prefix
  can pull anything the token can read into quarantine, so the token must be
  scoped to what may be ingested. No new role or restriction is added.

Not in this change: GitHub App installation tokens (a later credential kind
under the same prefix matching), SSH keys, per-marketplace credentials,
credentials in the database, and git credential helpers.

## Why the surface grows

`ConfigSurfaceBudgetTests` counts one new leaf,
`skills-gateway.ingestion.upstream-credentials` (a list is one leaf), so
`BUDGET` goes from 108 to 109.

- **No existing leaf can express it.** The only credential leaves today are
  `mirror.username` and `mirror.token`. They are for one outbound push target,
  and reusing them for inbound reads would widen the mirror credential's use.
  The object-store credentials are for storage. Nothing else carries a secret
  for an upstream.
- **The capability map absorbs the capability.** Private upstreams are a
  property of the existing *Registration* and *Ingestion* rows, and their
  trust boundary is *Registration*. The Registration row's text grows by a
  clause. No new area, backend package, estate object type, role or sweep is
  added.
- **One list, not a block of knobs.** The redirect policy, the loopback
  exception and the startup checks are fixed behaviour, not settings.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds
  - GW_INGEST_0050 — A private upstream is read with the credential configured for its URL prefix
  - GW_INGEST_0051 — An upstream credential is sent only under its own prefix
  - GW_INGEST_0052 — An upstream credential is never repeated by the gateway
  - GW_INGEST_0053 — The gateway refuses to start with an unusable upstream credential
  - GW_INGEST_0054 — Registration refuses a clone URL that embeds a credential
  - GW_INGEST_0055 — The registration ledger entry names the credential prefix it used

## Impact

- **Server:**
  - `ingestion/UpstreamCredentials` (new): validation, matching, and a
    per-request connection factory.
  - `UpstreamGit.connect` gains the URL and installs the factory.
  - `UpstreamFailure` scrubs configured token values from the root cause.
  - `MarketplaceRegistrationService`: refuses userinfo, and records the prefix
    in the ledger detail.
  - `SkillsGatewayProperties.Ingestion` gains the list and a record whose
    `toString` omits the token.
- **Schema, API contract, portal:** unchanged. The `400` for a userinfo URL is
  an existing status on registration.
- **Behaviour:** a registration URL with userinfo is now refused with `400`.
  An estate declaration with one fails as an entry.
- **Tests:** `GitHttpFixture` can require a Basic credential and records the
  `Authorization` header of every request.
- **Docs:** the configuration reference, registering a marketplace, trust
  boundaries, the capability map, and deployment examples for local
  development, Helm and ECS/Fargate.
