# Tasks: multi-replica-sessions

## 1. Requirements

- [x] 1.1 Add GW_AUTH_0053, GW_AUTH_0054, GW_OBSERVABILITY_0005 and SVC_GW_AUTH_0053,
  SVC_GW_AUTH_0054, SVC_GW_OBSERVABILITY_0005 (automated tests) to `docs/reqstool/`; verify with
  `reqstool status local -p docs/reqstool` (all three listed) and `openspec validate --all --strict`.

## 2. Probe under the prefix

- [x] 2.1 Test (S3 dev service) that the probe of a storage with a prefix writes only under it.
  Watch it fail.
- [x] 2.2 `ObjectStoreClient.probe(String keyPrefix)`, passed by `ObjectStoreGitStorage`; update the
  test delegates; 2.1 passes.
- [x] 2.3 Storage guide IAM section: a grant scoped to the prefix is enough; `mkdocs build --strict`.

## 3. Sessions in the database

- [x] 3.1 Test: two gateway instances on one database. The sign-in starts on one and the callback
  completes on the other against a stub provider, and the session cookie then authenticates
  `/api/v1/me` on both. Also: a session row whose attribute cannot be deserialized yields 401, not
  500. Watch it fail.
- [x] 3.2 Add `@SVCs({"SVC_GW_AUTH_0053"})` to the test methods from 3.1.
- [x] 3.3 `spring-boot-starter-session-jdbc`, the Spring Session tables in `V1__init.sql`,
  `spring.session.jdbc.initialize-schema: never`, the tolerant conversion service; 3.1 passes and
  `SessionCookieTests` expects `SESSION`.
- [x] 3.4 Add `@Requirements({"GW_AUTH_0053"})` to the session configuration from 3.3.
- [x] 3.5 Docs: storage guide's multi-replica section (sessions are shared, no affinity needed),
  configuration reference's session-cookie row, identity-providers guide.

## 4. Failed sign-in

- [x] 4.1 Test: a callback with no sign-in in progress, a callback carrying `error=access_denied`,
  and a token endpoint failure each get a 401 page naming their class, their code and a start-again
  link, and log WARN with the registration id and the code. Watch it fail.
- [x] 4.2 Add `@SVCs({"SVC_GW_AUTH_0054"})` to the test methods from 4.1.
- [x] 4.3 The failure handler, wired into `oauth2Login`; 4.1 passes.
- [x] 4.4 Add `@Requirements({"GW_AUTH_0054"})` to the failure handler's entry method from 4.3.
- [x] 4.5 Docs: identity-providers guide, "When sign-in fails".

## 5. Lease counter

- [x] 5.1 Test: a taken and a refused lease each increment `skills_gateway.sweep.lease` with their
  pass and outcome. Watch it fail.
- [x] 5.2 Add `@SVCs({"SVC_GW_OBSERVABILITY_0005"})` to the test method from 5.1.
- [x] 5.3 Counter and DEBUG line in `SweepLeases.runIfLeader`; 5.1 passes.
- [x] 5.4 Add `GW_OBSERVABILITY_0005` to the `@Requirements` on `SweepLeases.runIfLeader`.
- [x] 5.5 Docs: observability reference metric row (drop "no metric reports it"), storage guide's
  lease section.

## 6. Gates, evidence, archive

- [ ] 6.1 All gates from `AGENTS.md`; `reqstool status` ends PASS; `evidence.md` with the result
  tails and the commit SHA.
- [ ] 6.2 Archive as the final commit.
