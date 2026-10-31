# Tasks: portal-polish

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_AUTH_0067–GW_AUTH_0070 and SVC_GW_AUTH_0078–SVC_GW_AUTH_0084; modify GW_INGEST_0037 and SVC_GW_INGEST_0037 in `docs/reqstool/`.

## 2. Server: browser error and sign-in pages

- [x] 2.1 Minimal-page renderer; `SignInFailureHandler` uses it (existing SVC_GW_AUTH_0054 tests stay green).
- [x] 2.2 Browser not-found advice (`@Requirements GW_AUTH_0067`) + MockMvc test (`@SVCs SVC_GW_AUTH_0078`): browser gets the portal entry with 404; `*/*`, JSON and every machine prefix keep problem+json. Watch it fail first.
- [x] 2.3 Host error valve (`@Requirements GW_AUTH_0067`) + real-HTTP test (`@SVCs SVC_GW_AUTH_0079`) sharing an existing context. Watch it fail first.
- [x] 2.4 Sign-in pages controller, default login page disabled, `GET /login` and `GET /logout` permitted (`@Requirements GW_AUTH_0068`) + test (`@SVCs SVC_GW_AUTH_0081`). Watch it fail first.

## 3. Portal

- [x] 3.1 Catch-all route and not-found page; e2e cold load of an unknown address (`@SVCs SVC_GW_AUTH_0080`).
- [x] 3.2 Tab title hook and `continue` stripping in the shell (`@Requirements GW_AUTH_0069`, test `@SVCs SVC_GW_AUTH_0082`).
- [x] 3.3 Unknown snapshot stated on Review and Snapshots (`@Requirements GW_AUTH_0069`, test `@SVCs SVC_GW_AUTH_0083`).
- [x] 3.4 Shared lifetimes; tokens page lifetime choice and Expires column (`@Requirements GW_AUTH_0070`, test `@SVCs SVC_GW_AUTH_0084`).
- [x] 3.5 Review queue waiting time, finding count, sorting (`@SVCs SVC_GW_INGEST_0037`).
- [x] 3.6 Overflow: inventory description, audit principal, Adoption path; vetting step names whole; tests.
- [x] 3.7 Accessibility: skip link, `aria-sort`, webhook checkbox names; tests.

## 4. Documentation

- [x] 4.1 `reference/portal.md` and affected guides. Verify with `mkdocs build --strict`.

## 5. Gates and evidence

- [x] 5.1 Impeccable audit/harden on the touched pages; outcome in the PR.
- [x] 5.2 Every AGENTS.md gate fresh, recorded in `evidence.md`.
