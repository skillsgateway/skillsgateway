# Tasks: harden-marketplace-registration

## 1. Requirements SSOT

- [x] 1.1 Add GW_0016 (URL scheme allowlist) and GW_0017 (gateway-pinned ingestion ref) to docs/reqstool/requirements.yml
- [x] 1.2 Add SVC_GW_0016 and SVC_GW_0017 (automated-test) to docs/reqstool/software_verification_cases.yml

## 2. Implementation

- [x] 2.1 Add `allowed-url-schemes` (default http,https) to SkillsGatewayProperties
- [x] 2.2 Validate URL scheme in AdminController.registerMarketplace → HTTP 400, @Requirements({"GW_0016"})
- [x] 2.3 Add optional `ref` to RegisterMarketplaceRequest; reject any value other than the default branch (main) → HTTP 400, @Requirements({"GW_0017"})

## 3. Tests (SVC_GW_0016, SVC_GW_0017)

- [x] 3.1 Allow `file` scheme in AbstractGatewayTest properties; switch the HTTP registration test (SVC_GW_0001) to a file:// URI
- [x] 3.2 Test: ssh://, ext::, and scheme-less URLs are rejected with 400 and not persisted, @SVCs({"SVC_GW_0016"})
- [x] 3.3 Test: ref=feature-branch → 400 and not persisted; ref omitted and ref=main → 201, @SVCs({"SVC_GW_0017"})

## 4. Verification

- [x] 4.1 Run `./mvnw verify`, `reqstool status local -p docs/reqstool` (17/17 PASS), `openspec validate --all --strict`
