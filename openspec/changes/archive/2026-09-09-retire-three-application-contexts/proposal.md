# Proposal: retire-three-application-contexts

## Why

[#305](https://github.com/skillsgateway/skillsgateway/issues/305) is the cure for
the suite that exhausted CI's heap in
[#302](https://github.com/skillsgateway/skillsgateway/issues/302): nearly every
test class starts a full application context, and the number of *distinct* ones
had grown until Spring's test context cache stopped evicting. It asks for the
work incrementally — *"one area per change, with the before/after numbers in the
PR body"*.

[#327](https://github.com/skillsgateway/skillsgateway/pull/327) was the first
increment. It made the count measurable (`ContextBudgetTests` resolves each test
class's `MergedContextConfiguration` — the framework's own cache key — without
loading anything) and collapsed five contexts, from 36 to 31. #325 then added
one back for a posture that genuinely needed it, leaving the budget at 32.

**The suite is now sitting exactly on its budget.** 32 contexts against a
`BUDGET` of 32 means the next class carrying a new property set fails the build.
That is the ratchet working as designed, but it is also the signal that this
increment was due.

This change retires three more contexts, 32 → 29. It deliberately takes only
conversions that need no new dependency and no new pattern — each one is an
existing in-repo idiom applied to one more class.

**Observable behaviour of the gateway is unchanged.** No production source file
is touched, no `@SVCs` annotation moves off the test method it was on, and no
assertion is dropped or loosened.

## What Changes

### Three contexts retired

| Class | Was | Now |
| --- | --- | --- |
| `MachineLedgerTests` | its own `skills-gateway.roles.admins` naming eight principals | shares `AbstractNamedAdminsTest`, whose list gains those eight names |
| `ClaimMappedRoleIsEnforcedTests` | its own claim + one mapping | shares `AbstractClaimMappingTest` with `ClaimRoleMappingTests`, which already declared that mapping |
| `ExternalConnectorRegistrationTests` | a full `@SpringBootTest` for one in-memory assertion | an `ApplicationContextRunner` over the registrar, the connectors and the service that orders them |

The first two continue #327's `AbstractNamedAdminsTest` pattern exactly: the
*names* stay distinct, only the *declaration* is shared. The third is the
`ApplicationContextRunner` idiom already used by `RoleBootstrapGuardTests`,
`DevInsecureAuthGuardTests`, `RemovedPropertyGuardTests` and
`StorageBackendSelectionTests`.

### Two corrections to the guidance in `local-development.md`

The guide currently tells the next person that a slice — `@WebMvcTest`,
`@JdbcTest`, `@RestClientTest` — is the answer to "does this need a full
context". Both halves of that need qualifying, and both were established by
measurement here rather than by reading:

1. **A slice for a class that shares the base context makes the count worse.**
   The base context is built either way, so the slice is a 30th context rather
   than a replacement. Slices only pay on the classes that own a context today.
   This matters because #305's own comment nominates `WebhookTests` as the first
   conversion, and `WebhookTests` shares the base context.
2. **The slice annotations are not on the classpath.** Boot 4.1.1 ships only
   `@JsonTest` in `spring-boot-test-autoconfigure`; the other three moved to
   `spring-boot-webmvc-test`, `spring-boot-jdbc-test` and
   `spring-boot-restclient-test`, none of which this project depends on.

So the guide now leads with the conversion that does remove a context outright —
an `ApplicationContextRunner`, whose context is created and closed per test and
never enters the cache — and records the slice caveats beneath it, including the
`@AutoConfigureTestDatabase(replace = NONE)` trap #305 already identified.

## What this change deliberately does not do

- **No slice adoption.** Every slice needs a new test dependency; that is a
  change with its own argument to make, not a rider on this one.
- **`spring.test.context.cache.maxSize` stays at 8.** #305 asks for it to be
  revisited "once contexts are down". 29 is not materially below 32, so there is
  nothing here to revisit it *against* — the number should move when a measured
  larger cache is measurably faster, not because the count moved by three.
- **`TokenTtlCapTests` stays a full context**, though it was nominated as a
  plain unit test. Its accepted path asserts that a requested deadline is stored
  untouched at microsecond precision — a `TIMESTAMPTZ` round-trip through the
  real database. Mocking the repository would delete that verification, which
  #305's own constraints forbid.
- **The forwarded-headers group stays six contexts.** Folding
  `ForwardedHeadersFrameworkTests` into `ForwardedHeadersRelativeRedirectsTests`
  would save one, but only by asserting the plain `framework` posture from a
  context that also sets `server.tomcat.use-relative-redirects=true`. One
  posture per class is what makes that group's answers attributable.
- **`EscapeHatchRoleNegativeTests` keeps its own context.** It would fit
  `AbstractClaimMappingTest`'s single-administrator list today, but its
  assertion is that the principal `dev` holds no role at all, and that is only
  honest while no list its context can see names `dev`. Coupling it to a context
  maintained for another purpose makes a future edit there silently turn a deny
  assertion into a tautology.

## Impact

- Distinct application contexts: **32 → 29**. `BUDGET` lowered to 29.
- No production code, no API, no configuration surface, no UI.
- `docs/manual/guides/local-development.md` — the context-budget guidance.
