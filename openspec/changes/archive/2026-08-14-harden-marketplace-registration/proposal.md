# Proposal: harden-marketplace-registration

## Why

Registration is the gateway's trust boundary for untrusted input, and two gaps remain
before any untrusted registration can be accepted (issue #4 step 3 plus an explicit
owner directive): the clone URL scheme is unrestricted (git-URL injection surface —
ssh, file, and C-git's ext:: family), and nothing states that the ingested ref is the
gateway's decision rather than the consumer's.

## What Changes

- Marketplace clone URLs are validated against a configurable scheme allowlist
  (default: `http`, `https`); any other scheme — or a scheme-less path — is rejected
  with HTTP 400 and nothing is persisted (GW_0016).
- The gateway pins ingestion to the upstream default branch. A registration request
  that supplies a `ref` other than the default branch (`main`) is rejected with
  HTTP 400; the consumer cannot override the ref (GW_0017, owner directive).
- Existing tests that register scheme-less filesystem paths over HTTP switch to
  `file://` URIs, with `file` added to the allowlist only in the test configuration.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `marketplace-ingestion`: two ADDED requirements — GW_0016 (URL scheme allowlist)
  and GW_0017 (gateway-pinned ingestion ref).

## Impact

- `docs/reqstool/requirements.yml`, `software_verification_cases.yml`: GW_0016/0017,
  SVC_GW_0016/0017.
- `AdminController` (registration validation), `SkillsGatewayProperties`
  (`allowed-url-schemes`), `AbstractGatewayTest` / `IngestionTests`.
- No schema or facade changes.
