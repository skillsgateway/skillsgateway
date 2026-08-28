# Tasks: machine-api-credentials

This change authenticates the control plane, so it is old-coder Tier 3. Two
rules govern every test task below and are not negotiable:

- **RED before GREEN.** Write the test, run it, and *watch it fail for the
  stated reason* before writing the implementation. Paste the failure into the
  evidence report. A test never seen failing proves nothing.
- **Negative before positive.** For every capability granted, the test that
  proves it is *not* granted elsewhere is written first.

## 1. Requirements SSOT

- [ ] 1.1 Author GW_0115–GW_0120 in `docs/reqstool/requirements.yml` — title,
      significance, description, rationale, categories, revision — as the only
      place their text exists.
- [ ] 1.2 Author SVC_GW_0115–SVC_GW_0120 in
      `docs/reqstool/software_verification_cases.yml`, each naming the
      verification it demands.
- [ ] 1.3 Confirm no other branch has claimed GW_0115–GW_0120 before pushing.

## 2. Specification (approved before any implementation)

- [ ] 2.1 Write the executable spec as a named test list: every scenario in
      groups 5–8 below stated with concrete inputs and concrete expected
      status codes, plus the invariants that must survive (SVC_GW_0011,
      SVC_GW_0012, SVC_GW_0064, SVC_GW_0102, SVC_GW_0104 all still pass).
- [ ] 2.2 Record the setup plan: no new dependencies are expected; if one
      becomes necessary, justify it in one line and get it approved before
      installing.
- [ ] 2.3 Obtain approval of the spec — not of the code — before task group 3.
      If the run is autonomous, record `spec approval: not obtained` in the
      evidence report and claim correspondingly lower confidence.

## 3. Persistence

- [ ] 3.1 Add `access_tokens.api_scopes TEXT` and the machine-owner column to
      `V1__init.sql` (folded, per `CLAUDE.md`), defaulting to the pre-change
      meaning: no API scope.
- [ ] 3.2 Extend `AccessToken` with `apiScopeList()` and a predicate, with a
      javadoc that states all three defaults together — fetch empty means every
      marketplace, push empty means nowhere, API empty means nothing.
      `@Requirements({"GW_0115"})`.
- [ ] 3.3 RED: a test asserting an existing row with null `api_scopes` yields an
      empty API scope list and grants no API reach. Watch it fail. (SVC_GW_0115)
- [ ] 3.4 Extend `TokenRepository` for the new columns and for an admin-scoped
      lookup that is not filtered by the caller's principal. (SVC_GW_0120)

## 4. Machine principal and issuance

- [ ] 4.1 Reserve the `machine:` principal namespace: refused as an
      identity-provider-derived principal, refused as a hand-typed grant
      principal. `@Requirements({"GW_0117"})`.
- [ ] 4.2 RED: a test that an identity-provider subject literally named
      `machine:x` cannot log in or hold a grant. Watch it fail. (SVC_GW_0117)
- [ ] 4.3 `TokenService` issuance for machine credentials: validates API scope
      values against the allowlist, **requires** an expiry, applies the
      configured lifetime cap, refuses API scope on a session-derived
      credential. `@Requirements({"GW_0115", "GW_0120"})`.
- [ ] 4.4 RED: issuance without an expiry is refused with 422 and never
      defaulted; issuance beyond the cap is refused, never clamped. Watch both
      fail. (SVC_GW_0120)
- [ ] 4.5 RED: issuance of a session-derived credential carrying API scope is
      refused at issue time. Watch it fail. (SVC_GW_0116)
- [ ] 4.6 Record the provisioning human as owner on the `token-created` ledger
      entry and on the administrative listing. (SVC_GW_0117)

## 5. The negative guarantee — adversarial, written first

Every task in this group is a test that must be seen failing before the chain
in group 6 exists, and passing after.

- [ ] 5.1 RED: a token with **empty fetch scope** — the every-marketplace form,
      the most permissive fetch grant in the system — is rejected on every
      `/api/**` endpoint. This is the headline case. (SVC_GW_0116)
- [ ] 5.2 RED: a token with a named fetch scope is rejected on `/api/**`.
      (SVC_GW_0116)
- [ ] 5.3 RED: a token with push scope and no API scope is rejected on
      `/api/**`. (SVC_GW_0116)
- [ ] 5.4 RED: a session-derived credential is rejected on `/api/**`.
      (SVC_GW_0116)
- [ ] 5.5 RED: the symmetric direction — a token holding only API scope is
      rejected by the facade chain and by the publication chain. (SVC_GW_0116)
- [ ] 5.6 RED: a revoked machine credential, and an expired one, are rejected on
      `/api/**`. (SVC_GW_0116, SVC_GW_0120)
- [ ] 5.7 RED: a request carrying **both** a bearer header and a `Cookie` header
      is refused rather than resolved to either credential. (SVC_GW_0116)
- [ ] 5.8 RED: a bearer header on the machine chain creates no session and sets
      no cookie on the response. (SVC_GW_0116)
- [ ] 5.9 RED: a browser session with no `Authorization` header still reaches
      `/api/**` exactly as before — the session chain is unchanged.
      (SVC_GW_0011)
- [ ] 5.10 RED: a garbage bearer value, an empty bearer value, and a bearer
      value that is a *valid* facade token all produce 401 with no distinction
      that reveals which. (SVC_GW_0116)

## 6. The machine API filter chain

- [ ] 6.1 Add the stateless `/api/**` chain to `SecurityConfig`, ordered before
      the web chain and matched additionally on a bearer `Authorization` header.
      `@Requirements({"GW_0116"})`.
- [ ] 6.2 Its provider authenticates **only** a token whose API scope list is
      non-empty — a precondition of authentication, not a later authorization
      rule. `@Requirements({"GW_0116"})`.
- [ ] 6.3 Write the CSRF comment in the shape the facade and publication chains
      use: STATELESS, no session, no cookie honoured, self-authenticating
      request. Do **not** extend or rely on the session chain's `/api/**`
      exemption.
- [ ] 6.4 Verify the session chain's configuration is byte-for-byte unchanged
      apart from ordering, and say so in the evidence report.
- [ ] 6.5 Turn group 5 green. Re-run the whole group and record it.

## 7. The endpoint allowlist

- [ ] 7.1 Implement API scope values as named endpoint groups, deny-by-default,
      enforced **independently of `skills-gateway.roles.enabled`**.
      `@Requirements({"GW_0118"})`.
- [ ] 7.2 RED: with `roles.enabled=false` — the default, where every
      `require*()` passes — a machine credential still reaches only its scoped
      endpoints. Watch it fail. This is the trap this change exists to avoid.
      (SVC_GW_0118)
- [ ] 7.3 RED, adversarial: a machine credential whose principal holds `admin`
      is still refused snapshot **approval**, snapshot **rejection**, **waiver**
      creation, marketplace **deregistration**, retention **soft-delete** and
      **restore**. One test per act; each seen failing first. (SVC_GW_0118)
- [ ] 7.4 RED: a machine credential cannot mint a credential for another
      principal. (SVC_GW_0118)
- [ ] 7.5 The enumerating test: reflect over every mapped controller method and
      assert each is either in a scope group or on an explicit unreachable list.
      A method in neither **fails the build**. (SVC_GW_0118)
- [ ] 7.6 RED: a newly added endpoint that is in neither list makes 7.5 fail —
      prove the guard bites by adding a throwaway endpoint, watching the build
      break, and removing it. Record both outputs.

## 8. Roles for a machine principal

- [ ] 8.1 Confirm by test that `ClaimRoleMapper` derives nothing for a machine
      credential, and that `config` and `grant` sources work unchanged for a
      `machine:` principal. `@Requirements({"GW_0119"})`. (SVC_GW_0119)
- [ ] 8.2 RED: effective authority is the **intersection** — a machine
      credential with a scope but without the role gets 403 once roles are
      enabled; with the role but without the scope it is refused regardless.
      (SVC_GW_0119)
- [ ] 8.3 RED, adversarial: a machine principal cannot grant a role **to
      itself**. Watch it fail. (SVC_GW_0119)
- [ ] 8.4 RED: `estate.grants` can declare a `machine:` principal's role with no
      new mechanism, and reconciliation attributes it to `config-reconciler`.
      (SVC_GW_0119)

## 9. Lifecycle

- [ ] 9.1 Rotation preserves the machine principal, the expiry deadline, and all
      **three** scope dimensions. `@Requirements({"GW_0120"})`.
- [ ] 9.2 RED: a rotated machine credential has identical API scope — neither
      widened nor dropped — and the old secret is dead before the new one is
      returned. Watch it fail. (SVC_GW_0120)
- [ ] 9.3 Admin-scoped listing and revocation for machine credentials, leaving
      the caller's own-token listing strictly own-principal as it is today.
      (SVC_GW_0120)
- [ ] 9.4 RED: a non-admin cannot list or revoke another principal's machine
      credential, and an admin can. (SVC_GW_0120)
- [ ] 9.5 RED: revocation takes effect on the next request, not on a cache
      expiry. (SVC_GW_0120)

## 10. Audit

- [ ] 10.1 Ledger entries from a machine credential carry the `machine:`
      principal as actor and the token id in the detail.
      `@Requirements({"GW_0117"})`.
- [ ] 10.2 RED: a ledger query distinguishes machine actors, human actors and
      `config-reconciler` with no schema change. (SVC_GW_0117)
- [ ] 10.3 RED: no ledger entry produced by a machine credential names the
      provisioning human as the actor — the human appears as owner at
      provisioning only. (SVC_GW_0117)

## 11. API surface and portal types

- [ ] 11.1 Provisioning endpoints for machine credentials; the token view gains
      the API scope list. Additive within the major — verify the compatibility
      gate stays green (`docs/manual/reference/compatibility.md`).
- [ ] 11.2 Regenerate `src/main/frontend/src/api/types.gen.ts`; no UI in this
      change.
- [ ] 11.3 Regenerate the published contract document and confirm it matches the
      served one (GW_0106).

## 12. Documentation (same PR)

- [ ] 12.1 `docs/manual/reference/api/index.md` — the line "Every `/api/**`
      endpoint requires an authenticated OIDC session" is no longer true.
      Replace it with the precise two-path statement and update the surface
      table.
- [ ] 12.2 `docs/manual/reference/api/tokens.md` — the API scope dimension,
      mandatory expiry, rotation semantics, admin administration.
- [ ] 12.3 `docs/manual/guides/declarative-estate.md` — the estate-versus-API
      rule from design decision 5, and machine credentials as deliberately
      API-only alongside personal access tokens.
- [ ] 12.4 `docs/manual/concepts/trust-boundaries.md` and
      `docs/manual/architecture.md` — the new chain and the negative guarantee.
- [ ] 12.5 `docs/manual/reference/configuration.md` — new keys.
- [ ] 12.6 A guide section showing a CI job and a Terraform-style flow, stating
      plainly that approval is not among the things it can do.

## 13. Gauntlet and evidence

- [ ] 13.1 After the **last** code edit, run every gate fresh, in order:
      `./mvnw clean verify`; `pnpm test:stories`; `pnpm e2e`;
      `reqstool status local -p docs/reqstool` (must end `PASS`);
      `openspec validate --all --strict`; `mkdocs build --strict`.
- [ ] 13.2 Mutation-test or otherwise stress the scope predicate and the chain
      matcher specifically: a surviving mutant there is a real hole, not a
      metric.
- [ ] 13.3 Confirm no existing SVC test was weakened or deleted.
- [ ] 13.4 Write `openspec/changes/machine-api-credentials/evidence.md`: the
      commands, the pasted result tails, the commit SHA, the RED-then-GREEN
      record for every adversarial test in groups 5, 7, 8 and 9, the spec
      approval line, and every contract clause mapped to a test or explicitly
      skipped with a reason.
- [ ] 13.5 Summarise the evidence in the PR body under **Evidence**.
- [ ] 13.6 Archive the change with `/opsx:archive` as the final commit, after
      implementation and gates.
