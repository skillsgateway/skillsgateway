# Tasks: reduce-test-contexts

## 1. Requirements (SSOT first)

- [x] 1.1 No new requirement and no new SVC: the change alters how the suite is
      shaped, not what the gateway does or what is verified. Every `@SVCs`
      annotation stays on the test method it was on.

## 2. Measure before

- [x] 2.1 `ContextBudgetTests`: resolve every Spring test class's
      `MergedContextConfiguration` without loading a context, count the distinct
      ones, write the breakdown to `target/context-budget.txt`
- [x] 2.2 Fail the build when the count crosses the budget, with a message that
      names the property sets and the questions to ask before raising it
- [x] 2.3 Record the baseline: distinct contexts, context builds (`missCount`),
      containers started, peak heap

## 3. Collapse the property sets nothing can observe

- [x] 3.1 `MachineCredentialAdminTests`: drop the duplicated
      `skills-gateway.roles.admins=owner` entry, merging it with
      `MachineRoleIntersectionTests`
- [x] 3.2 `RefAdvertisementTests`: drop `skills-gateway.catalog.name=catalog`,
      which is the value the properties record substitutes for an unset name,
      merging it into the base context
- [x] 3.3 `AbstractNamedAdminsTest`: one declaration of the administrator names
      for the six suites whose only difference was which names those were, with
      the three principals that must never appear in it named in its javadoc
- [x] 3.4 Point `AdoptionTests`, `FourEyesTests`, `MachineApiScopeTests`,
      `MachineCredentialAdminTests`, `MachineCredentialLifecycleTests` and
      `MachineRoleIntersectionTests` at it; leave `RoleEnforcementTests` on its
      own context and say why in its javadoc

## 4. Boot 4 testing APIs

- [x] 4.1 `AbstractForwardedHeadersTest`: `RestTestClient.bindToServer(...)` in
      place of the hand-rolled `RestClient`, keeping
      `JdkClientHttpRequestFactory` explicit because the redirect is the response
      under test
- [x] 4.2 Sweep for `TestRestTemplate` — there is none in the repository, and
      `@AutoConfigureRestTestClient` does not exist in Spring Boot 4.1.1

## 5. Documentation and build comments

- [x] 5.1 `guides/local-development.md`: the context budget beside the gates,
      what it measures, and what to do when it fails
- [x] 5.2 `pom.xml`: the `spring.test.context.cache.maxSize` comment stops
      quoting a number nobody measured, points at the test that maintains it, and
      records that 8 was chosen to fit rather than from measurement

## 6. Measure after, and the gates

- [x] 6.1 Re-run the budget test and set the constant to the measured figure
- [x] 6.2 `./mvnw clean verify`
- [x] 6.3 `pnpm test:stories`, `pnpm e2e`
- [x] 6.4 `reqstool status local -p docs/reqstool` — must end `PASS`
- [x] 6.5 `openspec validate --all --strict`, `mkdocs build --strict`
- [x] 6.6 `evidence.md` with the before/after numbers and the gate tails
