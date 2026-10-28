# Tasks: upstream-requests-stay-on-origin

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_INGEST_0075 and SVC_GW_INGEST_0075 in `docs/reqstool/`.

## 2. Pin upstream requests to the origin (SVC_GW_INGEST_0075)

- [x] 2.1 `UpstreamRedirectTests`, with two loopback servers. A listing
  (anonymous and credentialed) and a fetch redirected to the other server fail,
  and the other server receives nothing. A same-origin redirect is still
  followed. Watch it fail first.
- [x] 2.2 `UpstreamOriginFactory`, installed by `UpstreamGit.connect` for every
  HTTP transport, wrapping the selected credential's factory
  (`@Requirements GW_INGEST_0075`).
- [x] 2.3 The cross-port cases of `UpstreamCredentialsIntegrationTests` and
  `UpstreamGitHubAppIntegrationTests` expect the refusal and no request at the
  other port.

## 3. Documentation

- [x] 3.1 `guides/private-upstreams.md` and `reference/configuration.md` state
  that a redirect off the registered origin is refused. Verify with
  `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
