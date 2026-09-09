# Tasks: retire-three-application-contexts

## 1. Requirements (SSOT first)

- [x] 1.1 No new requirement and no new SVC: the change alters how the suite is
      shaped, not what the gateway does or what is verified. Every `@SVCs`
      annotation stays on the test method it was on.

## 2. Measure before

- [x] 2.1 Run `ContextBudgetTests` on `main` and record the baseline: **32**
      distinct contexts, against a `BUDGET` of 32

## 3. Share a posture that already exists

- [x] 3.1 `AbstractNamedAdminsTest`: add `MachineLedgerTests`' eight principals
      to the shared administrator list, none of which is one of the three
      reserved names its javadoc forbids
- [x] 3.2 `MachineLedgerTests`: extend `AbstractNamedAdminsTest`, drop its own
      `@TestPropertySource`, and say in its javadoc why fixed principal names
      stay safe in a database it now shares
- [x] 3.3 `AbstractClaimMappingTest`: one declaration of the claim and mappings
      for the two suites that verify claim-derived roles, with the
      single-administrator list's rationale and the rule for adding a mapping
- [x] 3.4 Point `ClaimRoleMappingTests` and `ClaimMappedRoleIsEnforcedTests` at
      it; check that the narrower administrator list sharpens rather than
      weakens the latter's refusal

## 4. Retire a context that never needed the gateway

- [x] 4.1 `ExternalConnectorRegistrationTests`: an `ApplicationContextRunner`
      over `ExternalVettingConfiguration`, the built-in connectors and
      `VettingService`, with the run machinery mocked because neither
      `connectors()` nor `chainIdentity()` reaches it
- [x] 4.2 Confirm the chain as production assembles it stays verified where it
      was — `VettingTests` and `ConnectorToggleTests` walk
      `vettingService.connectors()` in the real context

## 5. Documentation

- [x] 5.1 `guides/local-development.md`: lead with the `ApplicationContextRunner`
      conversion, and record the two slice caveats — a slice for a base-context
      class adds a context, and the slice annotations are not on the classpath

## 6. Measure after, and the gates

- [x] 6.1 Re-run the budget test and set `BUDGET` to the measured figure
- [ ] 6.2 `./mvnw clean verify`
- [ ] 6.3 `pnpm test:stories`, `pnpm e2e`
- [ ] 6.4 `reqstool status local -p docs/reqstool` — must end `PASS`
- [ ] 6.5 `openspec validate --all --strict`, `mkdocs build --strict`
- [ ] 6.6 `evidence.md` with the before/after numbers and the gate tails
