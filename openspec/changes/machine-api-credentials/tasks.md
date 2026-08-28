# Tasks: machine-api-credentials

This change authenticates the control plane, so it is old-coder Tier 3. Two
rules govern every test task below and are not negotiable:

- **RED before GREEN.** Write the test, run it, and *watch it fail for the
  stated reason* before writing the implementation. Paste the failure into the
  evidence report. A test never seen failing proves nothing.
- **Negative before positive.** For every capability granted, the test that
  proves it is *not* granted elsewhere is written first.

## 0. Prerequisite — ships as its own PR, before this change

- [ ] 0.1 In a separate, small PR: a reflective test that enumerates every
      mapped `/api/**` controller method and asserts each is classified in a
      machine-reachability registry — either in a named scope group or on the
      explicit unreachable list. A method in neither **fails the build**.
- [ ] 0.2 In that PR: prove the guard bites. Add a throwaway endpoint, watch the
      build break, remove it, record both outputs.
- [ ] 0.3 Rationale for the split, to be restated in that PR's body: the test
      touches every controller, and bundling it here would make a
      trust-boundary review harder. Merge it first; this change then only adds
      rows to a registry that already exists and is already enforced.
- [ ] 0.4 Do not start task group 3 until 0.1 has merged.

## 1. Requirements SSOT

- [ ] 1.1 Author GW_0126–GW_0131 in `docs/reqstool/requirements.yml` — title,
      significance, description, rationale, categories, revision — as the only
      place their text exists.
- [ ] 1.2 Author SVC_GW_0126–SVC_GW_0131 in
      `docs/reqstool/software_verification_cases.yml`, each naming the
      verification it demands.
- [ ] 1.3 Confirm no other branch has claimed GW_0126–GW_0131 before pushing.
      **Renumbered from GW_0115–GW_0120, which collided.** `origin/main` already
      carries GW_0120, GW_0121 and GW_0122 (the Helm chart work), and
      `feat/pluggable-git-storage` reserves GW_0111, GW_0112, GW_0114 and
      GW_0115, while `chore/native-postgres-enum-types` takes GW_0125. This
      range starts above every id claimed anywhere. The unallocated gaps left
      behind — GW_0113 (dropped by #131 when #134 superseded it), GW_0116–GW_0119,
      GW_0123 and GW_0124 — are deliberate: an id is never reused, so a gap is
      cheaper than the ambiguity of recycling one.

## 2. Specification (approved before any implementation)

- [ ] 2.1 Write the executable spec as a named test list: every scenario in
      groups 6–10 stated with concrete inputs and concrete expected status
      codes, plus the invariants that must survive (SVC_GW_0011, SVC_GW_0012,
      SVC_GW_0064, SVC_GW_0102, SVC_GW_0104 all still pass).
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
      `@Requirements({"GW_0126"})`.
- [ ] 3.3 RED: a test asserting an existing row with null `api_scopes` yields an
      empty API scope list and grants no API reach. Watch it fail. (SVC_GW_0126)
- [ ] 3.4 Extend `TokenRepository` for the new columns and for an admin-scoped
      lookup that is not filtered by the caller's principal. (SVC_GW_0131)

## 4. Ledger actor type

- [ ] 4.1 Add `fetch_log.actor_type TEXT NOT NULL` to `V1__init.sql`, with a
      column comment stating why it is denormalised: the ledger is append-only
      history and must say what it meant after the credential it names is gone
      — the same reasoning the existing `token_id` comment already gives.
      `@Requirements({"GW_0128"})`.
- [ ] 4.2 Backfill: `config-reconciler`, `scheduler` and `system` become
      `actor_type = 'system'`; everything else `'human'`.
- [ ] 4.3 Set `actor_type` in **one place** — `AdminAuditLogger`, derived from
      the authentication — rather than passing it from each of the ~25 call
      sites. Update `EstateReconciler`, `SyncService` and `WaiverService` to
      declare `system` explicitly instead of relying on their magic strings.
- [ ] 4.4 RED: entries from a machine credential carry `actor_type='machine'`
      and the human-readable credential name in `principal`. (SVC_GW_0128)
- [ ] 4.5 RED: a ledger query separates human, machine and system actors with
      no string parsing and no join to `access_tokens`. (SVC_GW_0128)
- [ ] 4.6 RED: a ledger row still reports its actor correctly after the
      credential it names has been revoked **and its row deleted**. This is the
      whole argument for denormalisation and it gets an explicit test.
      (SVC_GW_0128)
- [ ] 4.7 Populate `token_id` on machine-API entries — NULL on admin entries
      today — so a leak trace has per-credential resolution. (SVC_GW_0128)
- [ ] 4.8 RED: no ledger entry produced by a machine credential names the
      provisioning human as the actor; the human appears as owner at
      provisioning only. (SVC_GW_0128)

## 5. Issuance

- [ ] 5.1 `TokenService` issuance for machine credentials: validates every API
      scope value against the known set, **requires** an expiry, applies the
      configured lifetime cap, refuses API scope on a session-derived
      credential. `@Requirements({"GW_0126", "GW_0131"})`.
- [ ] 5.2 RED: an unknown or misspelled scope value is refused at issue time
      with 422 — it must fail loudly, never silently never match, exactly as
      fetch scopes do today. (SVC_GW_0126)
- [ ] 5.3 RED: there is no wildcard. A request for `*`, `all`, or an empty list
      is refused or grants nothing; none of them grants everything.
      (SVC_GW_0126)
- [ ] 5.4 RED: issuance without an expiry is refused with 422 and never
      defaulted; issuance beyond the cap is refused, never clamped. Watch both
      fail. (SVC_GW_0131)
- [ ] 5.5 RED: issuance of a session-derived credential carrying API scope is
      refused at issue time. Watch it fail. (SVC_GW_0127)
- [ ] 5.6 Record the provisioning human as owner on the `token-created` ledger
      entry and on the administrative listing. (SVC_GW_0128)

## 6. The negative guarantee — adversarial, written first

Every task in this group is a test that must be seen failing before the chain
in group 7 exists, and passing after.

- [ ] 6.1 RED: a token with **empty fetch scope** — the every-marketplace form,
      the most permissive fetch grant in the system — is rejected on every
      `/api/**` endpoint. This is the headline case. (SVC_GW_0127)
- [ ] 6.2 RED: a token with a named fetch scope is rejected on `/api/**`.
      (SVC_GW_0127)
- [ ] 6.3 RED: a token with push scope and no API scope is rejected on
      `/api/**`. (SVC_GW_0127)
- [ ] 6.4 RED: a session-derived credential is rejected on `/api/**`.
      (SVC_GW_0127)
- [ ] 6.5 RED: the symmetric direction — a token holding only API scope is
      rejected by the facade chain and by the publication chain. (SVC_GW_0127)
- [ ] 6.5a RED **first, and expect it to expose a live hole**: a token holding
      only API scope and an **empty fetch scope list** must reach no marketplace
      through the facade. `AccessToken.permitsMarketplace` today returns
      `scopeList.isEmpty() || scopeList.contains(marketplace)`, so this asserts
      the opposite of current behaviour and 6.5 cannot go green without it.
      (SVC_GW_0127)
- [ ] 6.5b GREEN: make the fetch default conditional on the credential's shape —
      an empty fetch list means every marketplace only when `api_scopes` is
      empty, and nothing otherwise. State all three empty-list meanings together
      in the javadoc, as design decision 2's table does.
- [ ] 6.5c RED: an ordinary fetch token with an empty fetch scope list still
      reaches every marketplace — the pre-existing default is preserved for
      credentials that hold no API scope. This is the regression guard on 6.5b.
      (SVC_GW_0064)
- [ ] 6.6 RED: a revoked machine credential, and an expired one, are rejected on
      `/api/**`. (SVC_GW_0127, SVC_GW_0131)
- [ ] 6.7 RED: a request carrying **both** a `Bearer` header and a `Cookie`
      header is refused rather than resolved to either credential. (SVC_GW_0127)
- [ ] 6.8 RED: a bearer request creates no session and sets no cookie on the
      response. (SVC_GW_0127)
- [ ] 6.9 RED: a browser session with no `Authorization` header still reaches
      `/api/**` exactly as before — the session chain is unchanged.
      (SVC_GW_0011)
- [ ] 6.10 RED: a garbage bearer value, an empty bearer value, and a bearer
      value that is a *valid* facade token all produce 401 with no distinction
      that reveals which. (SVC_GW_0127)
- [ ] 6.11 RED: a facade token presented as HTTP **Basic** to `/api/**` does not
      authenticate either — the machine chain accepts only `Bearer`.
      (SVC_GW_0127)

- [ ] 6.12 RED: with `skills-gateway.dev-insecure-auth=true`, a garbage bearer
      token on `/api/**` is still refused. The escape hatch opens the web chain,
      never the machine chain — the facade's posture, applied here.
      (SVC_GW_0127)

## 7. The machine API filter chain

- [ ] 7.1 Add the stateless `/api/**` chain to `SecurityConfig`, ordered before
      the web chain and matched additionally on an `Authorization: Bearer`
      header. `@Requirements({"GW_0127"})`.
- [ ] 7.2 Its provider authenticates **only** a token whose API scope list is
      non-empty — a precondition of authentication, not a later authorization
      rule. `@Requirements({"GW_0127"})`.
- [ ] 7.3 Write the CSRF comment in the shape the facade and publication chains
      use: STATELESS, no session, no cookie honoured, self-authenticating
      request. Do **not** extend or rely on the session chain's `/api/**`
      exemption.
- [ ] 7.4 Verify the session chain's configuration is byte-for-byte unchanged
      apart from ordering, and say so in the evidence report.
- [ ] 7.5 Turn group 6 green. Re-run the whole group and record it.

## 8. Scopes and the allowlist

- [ ] 8.1 Implement the named scope values from `design.md` decision 3 as
      entries in the registry that task 0.1 already enforces, deny-by-default,
      enforced **independently of `skills-gateway.roles.enabled`**.
      `@Requirements({"GW_0129"})`.
- [ ] 8.2 RED: with `roles.enabled=false` — the default, where every
      `require*()` passes — a machine credential still reaches only its scoped
      endpoints. Watch it fail. This is the trap this change exists to avoid.
      (SVC_GW_0129)
- [ ] 8.3 RED, per scope: a credential holding exactly one scope reaches that
      scope's endpoints and is refused on **every other scope's** endpoints.
      One parameterised test over the full scope table, not a spot check — this
      is what proves the granularity is real and not decorative. (SVC_GW_0129)
- [ ] 8.4 RED: no scope implies another. `policy:write` alone does not confer
      `policy:read`; `marketplaces:register` alone does not confer
      `marketplaces:read`. (SVC_GW_0129)
- [ ] 8.5 RED: scopes compose additively — two scopes reach exactly the union
      of their endpoint sets and nothing more. (SVC_GW_0129)
- [ ] 8.6 RED, adversarial: a credential holding **every** scope at once, whose
      principal also holds `admin`, is still refused on each unreachable
      endpoint — approve, reject, waiver create, waiver delete, retention
      evaluate, retention compact, snapshot delete, snapshot restore, role
      grant, role revoke, and all of `/api/tokens/**`. One test per endpoint;
      each seen failing first. (SVC_GW_0129)
- [ ] 8.7 RED: re-vet (`vetting:run`) is reachable and publishes nothing —
      assert the snapshot's state is unchanged by a re-vet, which is why it is
      classified reachable while approval is not. (SVC_GW_0129)
- [ ] 8.8 RED: `GET /api/retention/candidates` is reachable while
      `POST /api/retention/evaluate` is not, and evaluate's soft-delete is
      demonstrably why. (SVC_GW_0129)
- [ ] 8.9 Record **every authorized read** of `GET /api/roles` on the ledger —
      a new behaviour: no read on this surface is logged today. One rule, no
      principal-type condition. Entry carries `actor_type`, the principal, and a
      `roles-read` event. `@Requirements({"GW_0128", "GW_0129"})`.
- [ ] 8.10 RED: a machine credential reading `/api/roles` writes exactly one
      ledger entry, with `actor_type='machine'` and its principal. Watch it
      fail. (SVC_GW_0128)
- [ ] 8.11 RED: a **human** session reading `/api/roles` also writes exactly one
      entry, with `actor_type='human'` and the identity-provider subject. The
      rule is uniform; `actor_type` is what separates the two at query time, not
      the presence or absence of a row. (SVC_GW_0128)
- [ ] 8.12 RED: a machine read of `GET /api/audit` writes no entry — reading the
      ledger must not append to the ledger, or a polling exporter grows a
      permanent floor of self-referential rows. This exclusion is about a
      feedback loop, not about volume, so it survives the uniform rule above.
      (SVC_GW_0128)
- [ ] 8.13 RED: the entry is written only on an authorized read; a 403 on
      `/api/roles` does not produce a `roles-read` entry. (SVC_GW_0128)

- [ ] 8.14 RED: every machine-credential provisioning, listing, rotation and
      revocation endpoint added by this change is unreachable by a machine
      credential holding all scopes **and** the `admin` role. They live under
      `/api/tokens/**` for exactly this reason; a credential that can mint a
      sibling can evade its own revocation. (SVC_GW_0129)

## 9. Roles for a machine credential

- [ ] 9.1 Confirm by test that `ClaimRoleMapper` derives nothing for a machine
      credential, and that `config` and `grant` sources work unchanged for its
      principal. `@Requirements({"GW_0130"})`. (SVC_GW_0130)
- [ ] 9.2 RED: effective authority is the **intersection** — a machine
      credential with a scope but without the role gets 403 once roles are
      enabled; with the role but without the scope it is refused regardless.
      (SVC_GW_0130)
- [ ] 9.3 RED: a machine credential cannot reach the grants API at all, so it
      cannot grant a role to anyone including itself. (SVC_GW_0130)
- [ ] 9.4 RED: `estate.grants` can declare a machine credential's principal with
      no new mechanism, and reconciliation attributes it to `config-reconciler`
      with `actor_type='system'`. This is the only route to a machine role.
      (SVC_GW_0130)

- [ ] 9.5 RED: minting a machine credential is refused for a session without the
      `admin` role **while `skills-gateway.roles.enabled` is false**, and the
      admin-scoped list and revoke paths are refused likewise. Under the default
      flag every `require*()` passes, which would let any OIDC user mint a
      credential that outlives their own IdP account. (SVC_GW_0130)

## 10. Lifecycle

- [ ] 10.1 Rotation preserves the machine identity, the expiry deadline, and all
      **three** scope dimensions. `@Requirements({"GW_0131"})`.
- [ ] 10.2 RED: a rotated machine credential has an identical API scope set —
      asserted per scope value, not as "the list is equal" — and the old secret
      is dead before the new one is returned. (SVC_GW_0131)
- [ ] 10.3 Admin-scoped listing and revocation for machine credentials, leaving
      the caller's own-token listing strictly own-principal as it is today.
      (SVC_GW_0131)
- [ ] 10.4 RED: a non-admin cannot list or revoke another principal's machine
      credential, and an admin can. (SVC_GW_0131)
- [ ] 10.5 RED: revocation takes effect on the next request, not on a cache
      expiry. (SVC_GW_0131)
- [ ] 10.6 RED: a machine credential whose requested expiry exceeds the built-in
      default cap is refused **when `skills-gateway.tokens.max-ttl` is unset**.
      `TokenService.validateTtl` returns early on a null cap today, so "mandatory
      expiry" currently admits a hundred-year credential. (SVC_GW_0131)
- [ ] 10.7 Record the built-in cap's value and its rationale in
      `docs/manual/reference/configuration.md`, next to
      `skills-gateway.tokens.max-ttl`.

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
- [ ] 12.2 `docs/manual/reference/api/tokens.md` — the API scope dimension, the
      full scope table, mandatory expiry, rotation semantics, admin
      administration.
- [ ] 12.3 Each endpoint page states whether a machine credential may reach it
      and under which scope, the way pages already state their role requirement.
- [ ] 12.4 `docs/manual/guides/declarative-estate.md` — the estate-versus-API
      rule from design decision 5, that role grants are estate-only, and machine
      credentials as deliberately API-only alongside personal access tokens.
- [ ] 12.5 `docs/manual/concepts/trust-boundaries.md` and
      `docs/manual/architecture.md` — the new chain, the negative guarantee, and
      the ledger's explicit actor type.
- [ ] 12.6 `docs/manual/reference/configuration.md` — new keys.
- [ ] 12.7 A guide section showing a CI job and a Terraform-style flow, stating
      plainly that approval, retraction and role granting are not among the
      things it can do.

## 13. Gauntlet and evidence

- [ ] 13.1 After the **last** code edit, run every gate fresh, in order:
      `./mvnw clean verify`; `pnpm test:stories`; `pnpm e2e`;
      `reqstool status local -p docs/reqstool` (must end `PASS`);
      `openspec validate --all --strict`; `mkdocs build --strict`.
- [ ] 13.2 Mutation-test or otherwise stress the scope predicate, the allowlist
      lookup and the chain matcher specifically: a surviving mutant there is a
      real hole, not a metric.
- [ ] 13.3 Confirm no existing SVC test was weakened or deleted.
- [ ] 13.4 Write `openspec/changes/machine-api-credentials/evidence.md`: the
      commands, the pasted result tails, the commit SHA, the RED-then-GREEN
      record for every adversarial test in groups 6, 8, 9 and 10, the spec
      approval line, and every contract clause mapped to a test or explicitly
      skipped with a reason.
- [ ] 13.5 Summarise the evidence in the PR body under **Evidence**.
- [ ] 13.6 Archive the change with `/opsx:archive` as the final commit, after
      implementation and gates.
