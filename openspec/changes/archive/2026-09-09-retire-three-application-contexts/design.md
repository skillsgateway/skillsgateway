# Design: retire-three-application-contexts

## The question this increment had to answer first

#305's plan is to convert full-context tests to slices. Before applying it a
second time, two of its premises were checked against the build rather than read,
and both need qualifying. Recording that here is most of this change's value; the
three conversions are the easy part.

### A slice is not automatically fewer contexts

The suite's 32 contexts are not 32 classes. 47 classes share one base context —
`AbstractGatewayTest`'s — and the other 24 contexts each exist for one class's
property set.

That asymmetry decides where slicing pays. Converting a class that *owns* a
context replaces an expensive context with a cheap one: a net win. Converting a
class that *shares* the base context adds a context, because the base is built
either way for the other 46. #305's own comment nominates `WebhookTests` as the
first conversion, and `WebhookTests` is in the shared 47 — so that conversion
would have moved the number the wrong way while looking like progress.

The rule that follows: **read `target/context-budget.txt` before choosing a
conversion target.** A class listed alone on its property set is worth
converting; a class in the 47-name group is not, at least not for the count.

### The slice annotations are not available

Spring Boot 4 split the test slices into per-technology modules. This project
depends on `spring-boot-test-autoconfigure`, which in 4.1.1 contains `@JsonTest`
and nothing else. `@WebMvcTest`, `@JdbcTest` and `@RestClientTest` are in
`spring-boot-webmvc-test`, `spring-boot-jdbc-test` and
`spring-boot-restclient-test`. All three are in the BOM the project already
imports; none is a dependency.

This is the same shape as the note already in `AbstractGatewayTest`, which builds
`MockMvc` by hand because `@AutoConfigureMockMvc` moved out of reach.

So every slice conversion begins with a new test dependency. That is a decision
with its own argument — which slice, why that one first, what it is allowed to
replace — and it does not belong as a rider on a change whose point is to
subtract. This increment therefore adopts no slice at all.

## The conversion that does subtract

`ApplicationContextRunner` is the third option, and it is better than a slice for
the case it fits: its context is created and closed inside the test method, so it
never enters the test context cache and never appears in the budget. A slice
context does enter the cache. For a class whose subject is *configuration*, the
runner is both the smaller context and the honest one — it contains the beans
under test and nothing that would make the assertion incidental.

`ExternalConnectorRegistrationTests` is exactly that case. Its subject is an
`ImportBeanDefinitionRegistrar` binding `skills-gateway.vetting.external[*]` off
the `Environment` while the context is being built, and its single assertion
reads an in-memory bean list. It cannot become a plain unit test, because the
registrar only runs during a real context refresh — but the context it needs
contains a registrar, four connectors and one service, not a web server, a
datasource and a PostgreSQL container.

The repository already had this pattern four times over
(`RoleBootstrapGuardTests`, `DevInsecureAuthGuardTests`,
`RemovedPropertyGuardTests`, `StorageBackendSelectionTests`). Nothing here is new
except one more class using it.

### What the runner gives up, and where that coverage went

Under `@SpringBootTest` the built-in connectors arrived by component scan; in the
runner they are named. So the converted test no longer proves that the scan finds
them. That claim was never this SVC's, and it stays covered where it was:
`VettingTests` and `ConnectorToggleTests` both walk
`vettingService.connectors()` in the real shared context and run the chain
against real content. The converted test's own claim — that a configured
external connector is bound, ordered ahead of the built-ins, and named in the
chain identity — is asserted against the same production code as before.

`VettingService`'s other six collaborators are mocked because neither
`connectors()` nor `chainIdentity()` reaches them. They are the run machinery,
and nothing here runs the chain.

## Why the two merges are safe, and what makes that a general rule

Contexts are this suite's only isolation between classes: no truncation, no
`@Sql`, no `@DirtiesContext`, and the Arconia dev service gives each context its
own database. So merging two contexts merges two databases, and *that* is the
hazard — not the administrator list, which is what the merge appears to be about.

The check each merge has to pass is therefore about assertions, not properties:
every assertion in the moving class must be scoped to a fixture it named itself,
or tolerant, or about something other than the database. `MachineLedgerTests`
passes because its principals are unique to it; its one global row-count
assertion is an equality across a single method, and JUnit is not configured for
parallel execution anywhere in this project.

The second, subtler check is the one `AbstractNamedAdminsTest`'s javadoc already
encodes: a merge must not make a *negative* assertion vacuous. Three suites
assert a principal holds no role at all, and each is honest only while no list
its context can see names that principal. None of the eight names added here is
one of them.

That check is also why `EscapeHatchRoleNegativeTests` is left alone even though
it would fit. Its principal `dev` is absent from `AbstractClaimMappingTest`'s
single-name administrator list *today*, so the merge would pass — and would leave
a security negative test whose honesty depends on a list maintained for an
unrelated purpose. The next person to add an administrator to the claim-mapping
suites would have no reason to look. A context is cheaper than that coupling.

## Rejected: the conversions that would have cost an assertion

- **`TokenTtlCapTests` as a Mockito unit test.** Its accepted path asserts that
  a requested deadline is stored untouched, truncated to microseconds because
  `TIMESTAMPTZ` stores micros and the round-trip must not depend on the
  platform clock's resolution. That assertion is *about the database*. A mocked
  repository would turn it into a check that the service passes an argument
  along, which is not the same claim.
- **Folding `ForwardedHeadersFrameworkTests` into
  `ForwardedHeadersRelativeRedirectsTests`.** Both run
  `server.forward-headers-strategy=framework`, so it saves a context — by
  asserting the plain `framework` posture from a context that also sets
  `server.tomcat.use-relative-redirects=true`. That group's value is that each
  answer is attributable to exactly one posture; #283 built it that way
  deliberately.
