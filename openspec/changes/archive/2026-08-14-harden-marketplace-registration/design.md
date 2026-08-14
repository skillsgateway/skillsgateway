# Design: harden-marketplace-registration

## Context

`POST /api/marketplaces` accepts `{name, url}` and persists after a name-pattern check
only. Ingestion (`IngestionService`) already fetches exclusively the upstream HEAD /
default branch — the consumer has no ref input today; GW_0017 makes that a stated,
tested contract at the HTTP boundary so it survives future API evolution. SVC tests
register local upstream fixtures: service-level via absolute paths, HTTP-level via a
scheme-less path string.

## Goals / Non-Goals

**Goals:** fail-closed URL scheme allowlist; explicit rejection (HTTP 400) of any
consumer-supplied ref other than the default branch.

**Non-Goals:** resolving or ingesting non-default refs (Phase 2 multi-ref publication);
credential/URL userinfo policing beyond the scheme; changes to the git facade.

## Decisions

- **Validate at the HTTP boundary (`AdminController`)**, not in the repository or
  `IngestionService`: registration is the trust boundary, and service-level test
  arrangement (local-path fixtures) keeps working. The JGit fetch itself never sees an
  unvetted URL because only registered marketplaces are ingested (GW_0001).
- **Allowlist configurable** via `skills-gateway.allowed-url-schemes` (default
  `http,https`). Tests add `file` and register `file://` URIs over HTTP. A URL with no
  scheme or an unparseable URL is rejected — fail closed. JGit lacks C-git's `ext::`
  transport, but the allowlist is required anyway (defense in depth, and the transport
  set can grow).
- **`ref` field on the registration request**: accepted only when absent or equal to
  the upstream default branch (`main` by convention); anything else → HTTP 400 with a
  problem detail explaining the ref is set by the gateway. Chosen over ignoring the
  field silently: silent ignoring would let a consumer believe a pinned ref was
  honored. HTTP 400 (not 422) per owner directive.
- **Both checks annotated `@Requirements({"GW_0016"}) / ({"GW_0017"})`** on dedicated
  validation methods so the traceability maps to the exact enforcement code.

## Risks / Trade-offs

- [`main` hardcoded as the accepted ref value while upstreams may default to `master`]
  → the check is about *override attempts*, not resolution; ingestion still follows the
  actual upstream HEAD. Accepting only the literal `main` (or absence) is the
  fail-closed reading of the directive; revisit if a `master`-defaulting upstream needs
  to state a ref explicitly (it doesn't — omit the field).
- [Scheme allowlist is not full URL vetting (no host policy, no userinfo ban)] → scoped
  to issue #4's requirement; host allowlisting is a future requirement.

## Migration Plan

Pure addition at the API boundary. Existing registrations are unaffected. Rollback =
revert.

## Open Questions

(none)
