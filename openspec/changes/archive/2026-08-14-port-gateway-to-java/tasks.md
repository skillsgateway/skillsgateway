# Tasks: port-gateway-to-java

## 1. Build additions

- [x] 1.1 Add spring-boot-starter-actuator dependency
- [x] 1.2 Add cyclonedx-maven-plugin (makeAggregateBom bound to package) so the jar carries the CycloneDX BOM
- [x] 1.3 Expose health + sbom management endpoints in application.yaml

## 2. Persistence (PostgreSQL, JdbcClient, Flyway)

- [x] 2.1 Flyway V1__init.sql: marketplaces, snapshots (state CHECK held/approved/rejected + decision/provenance columns), fetch_log (append-only), access_tokens (principal, name, token_hash, created_at, revoked_at)
- [x] 2.2 MarketplaceRepository (register/get/list); add `@Requirements("GW_INGEST_0001")` on the registering method (covers GW_INGEST_0001)
- [x] 2.3 SnapshotRepository with state-machine guard (only held → approved/rejected; invalid transitions rejected)
- [x] 2.4 FetchLogRepository (append/list) and TokenRepository (create/find-by-hash/revoke)

## 3. Git storage seam

- [x] 3.1 GitStorage interface (quarantine/published JGit Repository handles + lifecycle) with filesystem implementation — the seam a JGit-DFS implementation replaces later

## 4. Ingestion (marketplace-ingestion capability)

- [x] 4.1 Resolve upstream default branch (symref HEAD) and fetch into quarantine via JGit; pin refs/snapshots/<sha>; record held snapshot keyed by upstream SHA; add `@Requirements("GW_INGEST_0002", "GW_APPROVAL_0001")` (covers GW_INGEST_0002, GW_APPROVAL_0001 — re-ingestion never touches published)
- [x] 4.2 Read .claude-plugin/marketplace.json blob at the fetched commit (RevWalk/TreeWalk)
- [x] 4.3 Local-only source policy: reject any non-relative-path plugin source including unknown shapes (fail closed); add `@Requirements("GW_INGEST_0003")` (covers GW_INGEST_0003)

## 5. Approval (snapshot-approval capability)

- [x] 5.1 Approve/reject with reviewer identity + timestamp recorded via the state machine; add `@Requirements("GW_APPROVAL_0002")` (covers GW_APPROVAL_0002)
- [x] 5.2 On approval, copy the approved commit closure quarantine → published (in-process fetch of refs/snapshots/<sha>) and RefUpdate refs/heads/main (objects, never other refs)
- [x] 5.3 Provenance query (upstream URL, SHA, approver, timestamps); add `@Requirements("GW_INGEST_0004")` (covers GW_INGEST_0004)

## 6. Git façade (git-facade capability)

- [x] 6.1 Register GitServlet at /git/* with a resolver that only opens published repos and no receive-pack factory (read-only by construction); add `@Requirements("GW_FACADE_0001", "GW_FACADE_0002")` (covers GW_FACADE_0001, GW_FACADE_0002)
- [x] 6.2 PostUploadHook appends fetch_log records (identity, marketplace, ref, wanted SHAs, timestamp); add `@Requirements("GW_AUDIT_0001")` (covers GW_AUDIT_0001)

## 7. Auth (auth capability)

- [x] 7.1 Web/admin security filter chain: oauth2Login (OIDC), session cookie, unauthenticated → IdP redirect; add `@Requirements("GW_AUTH_0002")` (covers GW_AUTH_0002)
- [x] 7.2 Git security filter chain (/git/**): HTTP Basic where password = PAT; AuthenticationProvider hashing the presented token (SHA-256, high-entropy random tokens) with revocation check; add `@Requirements("GW_AUTH_0003")` (covers GW_AUTH_0003)
- [x] 7.3 Token endpoints: create (cleartext returned exactly once, hash persisted) and revoke, under the OIDC-authenticated chain; add `@Requirements("GW_AUTH_0004")` (covers GW_AUTH_0004)

## 8. Admin API (admin-api capability)

- [x] 8.1 /api endpoints: register marketplace, list marketplaces+snapshots with states, trigger ingest, approve/reject snapshot, provenance, audit listing; add `@Requirements("GW_AUTH_0001")` on the listing endpoint (covers GW_AUTH_0001)

## 9. SBOM (sbom capability)

- [x] 9.1 Verify the CycloneDX BOM lands in the jar and /actuator/sbom serves it; add `@Requirements("GW_API_0001")` on the exposing configuration (covers GW_API_0001)

## 10. Tests — one per SVC, each replacing scaffold SanityTest coverage

- [x] 10.1 Registration listed via admin API; add `@SVCs("SVC_GW_INGEST_0001")` (covers SVC_GW_INGEST_0001)
- [x] 10.2 Ingestion produces held snapshot keyed by upstream SHA; add `@SVCs("SVC_GW_INGEST_0002")` (covers SVC_GW_INGEST_0002)
- [x] 10.3 External-source manifest rejected and unapprovable; add `@SVCs("SVC_GW_INGEST_0003")` (covers SVC_GW_INGEST_0003)
- [x] 10.4 Upstream update leaves served content unchanged, new snapshot held; add `@SVCs("SVC_GW_APPROVAL_0001")` (covers SVC_GW_APPROVAL_0001)
- [x] 10.5 Approval records reviewer + timestamp; add `@SVCs("SVC_GW_APPROVAL_0002")` (covers SVC_GW_APPROVAL_0002)
- [x] 10.6 E2E: real `git clone` over HTTP (with PAT) matches approved content; add `@SVCs("SVC_GW_FACADE_0001")` (covers SVC_GW_FACADE_0001)
- [x] 10.7 Held/rejected content not advertised or fetchable; add `@SVCs("SVC_GW_FACADE_0002")` (covers SVC_GW_FACADE_0002)
- [x] 10.8 Every façade fetch appends an audit record incl. identity; add `@SVCs("SVC_GW_AUDIT_0001")` (covers SVC_GW_AUDIT_0001)
- [x] 10.9 Provenance query returns URL/SHA/approver/time; add `@SVCs("SVC_GW_INGEST_0004")` (covers SVC_GW_INGEST_0004)
- [x] 10.10 Admin listing returns marketplaces and snapshot states; add `@SVCs("SVC_GW_AUTH_0001")` (covers SVC_GW_AUTH_0001)
- [x] 10.11 Unauthenticated web request redirects to IdP; authenticated session succeeds (oidcLogin test support); add `@SVCs("SVC_GW_AUTH_0002")` (covers SVC_GW_AUTH_0002)
- [x] 10.12 Git fetch rejected without/with-invalid token, succeeds with valid PAT, identity audited; add `@SVCs("SVC_GW_AUTH_0003")` (covers SVC_GW_AUTH_0003)
- [x] 10.13 Token shown once, stored hashed, revocation takes effect; add `@SVCs("SVC_GW_AUTH_0004")` (covers SVC_GW_AUTH_0004)
- [x] 10.14 /actuator/sbom returns the CycloneDX document; add `@SVCs("SVC_GW_API_0001")` (covers SVC_GW_API_0001)

## 11. Native spike and traceability closure

- [x] 11.1 GraalVM native spike: `./mvnw -Pnative native:compile` + smoke clone against the native binary; record outcome (and any reachability metadata added) in ADR 0002
- [x] 11.2 Full verification: `./mvnw verify` green; `reqstool status local -p docs/reqstool` = 14/14 PASS; `openspec validate --all --strict` passes
