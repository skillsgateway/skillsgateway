# Tasks: client-discoverable-revocation

## 1. Requirements

- [x] 1.1 Add `GW_FACADE_0031` (a client can ask whether the content it holds is
      still approved) and `GW_FACADE_0032` (the check tells a caller nothing it
      could not already fetch) to `docs/reqstool/requirements.yml`, from the
      drafted text in `design.md — Requirement text to mint`.
- [x] 1.2 Re-confirm both ids are free at the moment of writing — against
      `requirements.yml` **and** every in-flight `openspec/changes/`, not just
      the former.
- [x] 1.3 State in `GW_FACADE_0032`'s rationale that the indistinguishability
      rule is inherited from `GW_AUTH_0006 — Marketplace-scoped access tokens`
      rather than newly invented, so the two cannot drift apart.
- [x] 1.4 Reference `GW_APPROVAL_0017` from `GW_FACADE_0031` for the reversed-
      revocation marker: this surface honors an existing obligation.
- [x] 1.5 Add the two matching SVCs.

## 2. The security chain

- [x] 2.1 A stateless chain for `/status/**` in `SecurityConfig`, modelled on
      `publishChain`: PAT over Basic, `STATELESS`, CSRF exempt with the same
      stated reason, no cookie honored, no session created.
- [x] 2.2 The IdP-bearer filter applies where
      `skills-gateway.facade.idp-bearer.enabled` is set, exactly as `gitChain`
      installs it — a client that can fetch can ask, whichever of the two
      credentials it fetches with.
- [x] 2.3 The Basic challenge stays Basic, for the reason `gitChain`'s javadoc
      already gives.
- [x] 2.4 Confirm the chain's `@Order` places it before the catch-all and does
      not shadow `/git/**` or `/publish/**`.

## 3. The lookup

- [x] 3.1 A repository query answering, for a batch of `(marketplace, sha)`
      pairs, the approval state, the revocation time where there is one, and
      whether the approval stands over a reversed administrative revocation.
- [x] 3.2 One round trip for the batch, not one per pair.
- [x] 3.3 Enforce the token's marketplace scope **before** the lookup, the order
      `GitFacadeConfiguration.resolvePublished` establishes, so an out-of-scope
      pair cannot be distinguished by timing either.
- [x] 3.4 Map every non-approved, non-revoked outcome to `unknown` at a single
      point, so a state added to `snapshots` later cannot leak through as a new
      answer.

## 4. The endpoint

- [x] 4.1 `POST /status/snapshots` in the existing `facade` package — no new
      package.
- [x] 4.2 Answers one result per requested pair, in the order asked, echoing the
      pair.
- [x] 4.3 `revoked` carries the time and **not** the reason.
- [x] 4.4 `approved` carries the reversed-revocation marker when one applies.
- [x] 4.5 Refuse a request over the fixed bound rather than truncating it; the
      bound is a constant, not a `skills-gateway.*` leaf.
- [x] 4.6 Refuse a malformed pair (a SHA that is not a 40-hex object id, a
      marketplace name failing the facade's own pattern) without revealing which
      of the two it was, since the name pattern is a scope-adjacent fact.
- [x] 4.7 No ledger write on this path — assert it, do not merely omit it.
- [x] 4.8 A counter per answered state, so an operator can see clients checking
      in and that clients are still holding withdrawn content.
- [x] 4.9 Decide and record whether the route appears in the published OpenAPI
      document; if it does, confirm the contract check sees it as additive.
- [x] 4.10 Regenerate the frontend API types with `pnpm gen:api-types` only if
      4.9 puts the route in the document.

## 5. Tests — negative first (`old-coder` discipline)

- [x] 5.1 Prove each test fails before its implementation exists.
- [x] 5.2 Unauthenticated request refused; a session cookie does not
      authenticate it; no session is created by a successful one.
- [x] 5.3 A scoped token asking about a marketplace outside its scope gets an
      answer **byte-identical** to the one for a marketplace that does not
      exist — asserted on the response, not on the status code alone.
- [x] 5.4 An unscoped token keeps its every-marketplace behavior
      (`GW_AUTH_0006`).
- [x] 5.5 Approved-but-superseded answers `approved`, not `unknown` and not a
      fourth state.
- [x] 5.6 A revoked snapshot answers `revoked` with the time, and the response
      contains no substring of the revocation reason.
- [x] 5.7 A held snapshot, a rejected one, one the gateway never had, and one
      whose record retention removed all answer identically.
- [x] 5.8 A snapshot approved over a reversed administrative revocation is
      distinguishable from one never withdrawn (`GW_APPROVAL_0017`).
- [x] 5.9 A revocation is observable through the check within the same request
      that follows it — the check reads the record, not a cache.
- [x] 5.10 A request one pair over the bound is refused, and none of its pairs
      is answered.
- [x] 5.11 No row is appended to the fetch ledger by any of the above, asserted
      by counting before and after.
- [x] 5.12 The deny-by-default guard for the `/status/**` prefix (design D8): a
      route added under it without a deliberate entry fails the suite.
- [x] 5.13 Reuse an existing `@SpringBootTest` property set — `ContextBudgetTests`
      caps distinct contexts at 32 and this change has no argument for raising it.

## 6. Documentation

- [x] 6.1 `docs/manual/guides/client-enforcement.md` — the other half of "the
      only door": how a client notices that what it already holds has been
      withdrawn, with a runnable check that treats `unknown` and any error as
      withdrawn.
- [x] 6.2 The REST API reference — the endpoint, its body, its states, every
      refusal, and that the reason is deliberately absent.
- [x] 6.3 Update the statement added by `administrative-revocation` that
      revocation "does not reach clients that already hold it" and links
      [#427](https://github.com/skillsgateway/skillsgateway/issues/427) — it is
      no longer true as written.
- [x] 6.4 `docs/manual/architecture.md` — the pull-side check in the enforcement
      section, beside the fleet-managed settings it complements.
- [x] 6.5 Note in the compatibility reference that `/status/**` is a served path
      prefix under the same additive contract as `/api/**`.
- [x] 6.6 Any new page placed by its Diátaxis type and added to `nav:`.

## 7. Gates and archive

- [x] 7.1 `./mvnw clean verify` — foreground, its own call.
- [x] 7.2 `(cd src/main/frontend && pnpm test:stories)`
- [x] 7.3 `(cd src/main/frontend && pnpm e2e)`
- [x] 7.4 `reqstool status local -p docs/reqstool` — must end `PASS`
- [x] 7.5 `openspec validate --all --strict`
- [x] 7.6 `mkdocs build --strict`
- [x] 7.7 `evidence.md` from one final fresh run after the last edit.
- [ ] 7.8 PR with an **Evidence** section, referencing
      [#427](https://github.com/skillsgateway/skillsgateway/issues/427) with a
      closing keyword; archive as the final commit.
