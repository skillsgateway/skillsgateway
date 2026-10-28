# Proposal: an upstream listing or fetch never leaves the registered origin

## Why

Registration lists a marketplace upstream and ingestion fetches it, both over
JGit. Over HTTP, JGit follows a redirect the upstream server answers with, to
any scheme, host or port. A credentialed upstream already keeps its token on
requests under its own prefix (`GW_INGEST_0051 — Upstream credentials ride
only on requests under their own prefix`). The request itself, though, still
goes wherever the redirect points. An anonymous upstream installed no
connection factory at all, so nothing decided where its hops went.

Where the gateway connects should be the administrator's choice, made at
registration, and not the choice of the server being read.

## What Changes

- New `GW_INGEST_0075 — An upstream listing or fetch never leaves the
  registered URL's origin`. Every HTTP request of a listing or fetch goes to
  the registered URL's scheme, host and port, with or without a credential. A
  redirect elsewhere is refused before anything connects there, and the
  registration or ingest fails with that reason.
- A redirect within the origin is still followed. A forge that moves a
  repository to another path keeps working.
- New `SVC_GW_INGEST_0075` covers three cases: a listing redirected to another
  origin (anonymous and credentialed), a fetch redirected to another origin,
  and a same-origin redirect that is still followed.
- **BREAKING** (pre-1.0) for an upstream that relies on a redirect to another
  host, port or scheme: it must be registered at its final URL.
- Two existing tests had registered across a cross-port redirect and seen
  requests arrive without the credential. They now expect the refusal, with no
  request at the other port:
  - `SVC_GW_INGEST_0051 — A redirect out of the prefix carries no credential`
  - `SVC_GW_AUTH_0051 — The assertion goes only to the API, never across a
    redirect or on a git request`

  Their claim (no credential outside the prefix) still holds, and more
  strictly. The same-origin path cases are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds `GW_INGEST_0075`.

## Impact

- New `UpstreamOriginFactory`, installed by `UpstreamGit.connect` on every
  HTTP transport. It wraps the credentialed factory when one is selected.
- Docs: `guides/private-upstreams.md` and `reference/configuration.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
