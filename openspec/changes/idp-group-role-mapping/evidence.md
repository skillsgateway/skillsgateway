# Evidence: idp-group-role-mapping

One final fresh run of every gate after the last code edit, against a clean
tree at `1453790e4b63c7c11a8921e198abbd1f90bd6e4c`. Edits after it are this
report, the tasks checklist, and the archive move — no source changes.

## Discipline notes (old-coder Tier 3 — this change decides what makes a session privileged)

- **Spec approval: not obtained (autonomous run).** The owner's brief was
  "implement #66"; the issue and its follow-up comment fixed the design rails
  (map by claim value not convention, auth stays in the app, `preferred_username`
  as the principal attribute, an Entra setup guide). The committed OpenSpec
  change — `proposal.md`, `design.md`, `specs/`, `tasks.md`, commit `1453790` —
  is the spec the implementation was held to, and it is offered for review after
  the fact rather than before. Confidence is claimed correspondingly lower: the
  correlation-breaking review that spec approval buys did not happen.
- **RED was not observed before GREEN.** Tests and implementation were written
  in the same pass, so no test in this change was watched failing before the
  code existed. That is a real gap and the mutation pass below is the
  compensating evidence, not a substitute for it: each mutant shows the suite
  *can* fail for the reason the test claims to check. Where the two differ —
  a test that is vacuous in a way no mutant happens to expose — this change has
  no evidence.
- **Failure model.** `design.md` lists eleven failure modes (F1–F11) with the
  layer that catches each. Every one is covered by a test except F10 (the
  principal-name attribute is mutable, so a rename orphans grants) which is not
  fixable in code and is documented in the guide instead, and F8 (a route ships
  ungated) which is carried by the pre-existing
  `RoleEnforcementTests.ROLE_GATED_MUTATIONS` route-table equality assertion —
  this change adds no route.
- **No existing SVC test was weakened.** Three pre-existing tests were edited
  for mechanical reasons only, with no assertion changed in meaning:
  `EstateReconciliationTests` and `LicensePolicyTests` for the widened
  `EffectiveRole` / `SkillsGatewayProperties` constructors, and
  `AbstractGatewayTest` to stop hard-coding `scope=openid` so the shipped
  `application.yaml` placeholder is what the suite exercises.

## Mutation pass

Eight mutants, each a plausible bug in the trust-boundary code, applied one at
a time and restored via `git checkout --` (restore verified by
`git diff --name-only` after each, and `grep -rn MUTANT src/` returning nothing
before this report was written). The runner fails closed: an anchor that does
not match exactly once, a surviving mutant, or a dirty tree after restore is a
hard failure.

| # | Mutant | Result |
| --- | --- | --- |
| 1 | `ClaimRoleMapper.rolesFrom` returns empty for every session | KILLED |
| 2 | Claim match widened from `equals` to `startsWith` (F1) | KILLED |
| 3 | Claim values no longer trimmed | KILLED |
| 4 | `ClaimRoleMapper.truncated` always false (F4) | KILLED |
| 5 | The unscoped-approver mapping check removed from startup validation (F5) | KILLED |
| 6 | `RoleService.effectiveRoles` lets a claim role overwrite the grant it duplicates | KILLED |
| 7 | `claimsOf` reads any credential's authorities as claim values, not only an `OidcUser`'s claims (F2) | KILLED |
| 8 | `OidcIdTokenValidation.validator` ignores the configured issuer (F3) | KILLED |

**Negative control.** A ninth "mutant" changed only a javadoc comment and
**SURVIVED**, as required — proving the runner distinguishes a killed mutant
from an unkilled one rather than reporting KILLED unconditionally. What that
buys, precisely: the runner's pass/fail path works. It does not prove the eight
mutants are the eight bugs most likely to be written.

Kills are attributed to whichever test fails first, so the score validates the
suite as a whole, not each layer in it.

## Adversarial pass

Attempted against the finished implementation, all covered by tests:

- Claim values that resemble a mapping by prefix, suffix, embedded substring,
  letter case, or an internal space — none grant anything.
- A claim-derived approver reaching another marketplace by name, through a bare
  snapshot id, and through a bare waiver id — refused on all three, by the same
  gateway-side resolution that carries SVC_GW_0069.
- A non-OIDC `Authentication` carrying `gw-admins` as a *granted authority*
  rather than a claim — derives nothing.
- An OIDC session carrying the mapped values under a different claim name than
  the one configured — derives nothing.
- 2000 generated claim payloads (nested maps, nulls, numbers, booleans, blank
  and whitespace strings, lists up to 50 near-miss tokens, depth-limited
  recursion) from a fixed seed: the matcher never throws, and its output is
  compared against an independently computed exact-match set every iteration.
  This is generative testing written by hand — the build has no property-based
  testing dependency, and none was added.
- A delimited string (`"gw-admins gw-auditors"`, `"gw-admins,gw-auditors"`)
  grants nothing, because splitting would mean inventing a delimiter.

## Known limits (declared, not covered)

- **The startup warning when no issuer is pinned is not asserted by a test.**
  The *decision* it reports is (`OidcIdTokenValidationTests` proves an unpinned
  validator compares no issuer); the log line itself is verified only by
  observation in the e2e gateway log. It is advisory output, not a failure mode.
- **`user-name-attribute` and `SGW_OIDC_SCOPE` are verified at the
  registration**, not through a real login with a changed principal claim:
  `OidcRegistrationConfigurationTests` sets the environment variables an
  operator would set and reads back the `ClientRegistration` the application
  built from the shipped `application.yaml`. What Spring Security then does with
  a `userNameAttributeName` is trusted, not re-tested.
- **No concurrency or load layer.** The mapper is stateless and allocation-only
  per request; the validated mapping table is immutable and built once at
  startup.
- **Claim size is unbounded by the gateway** — it is bounded by the token the
  provider already issued, and the scan is a hash-set lookup per mapping.
- **The overage rule recognises two shapes** — OpenID Connect's `_claim_names`
  and a `has<claim>` sibling boolean. A provider that signals truncation some
  third way is not detected, and would present as a session with no membership.

## Gates

### `./mvnw clean verify`

```
[INFO] Tests run: 154, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:18 min
```

### `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (27.8s)
```

All twelve run against a gateway with `skills-gateway.roles.enabled=true` whose
**only** admin mapping is the group claim the mock identity provider issues, so
the suite passes only if claim-to-role mapping works end to end through a real
login redirect. Test 12
(`the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim`,
`SVC_GW_0098`) names it explicitly by asserting `source: "claim"` on `/api/me`.
This is the stronger of the two arrangements `design.md` Decision 8 allowed;
the fallback was not needed.

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
97/97 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 26 passed, 0 failed (26 items)
```

### `mkdocs build --strict`

```
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.90 seconds
```

## Commit

Final implementation commit: `1453790e4b63c7c11a8921e198abbd1f90bd6e4c`
(every gate above ran against this tree; the archive commit follows).
