# Design — reduce-test-contexts

## Context

Spring caches a test's `ApplicationContext` under its `MergedContextConfiguration`.
Two test classes share a context if and only if that whole object is equal —
locations, classes, initializers, active profiles, property-source properties,
context customizers and loader. In this suite everything but the property array
is constant, so **the property array is the cache key**, and a difference of one
character in one entry is a second live application context.

In this project a live context is not only heap. `arconia-dev-services-postgresql`
registers its container per context, so every distinct property set is also its
own PostgreSQL container, started and torn down. The baseline run below created
36 PostgreSQL containers and 36 Floci containers for 125 test classes.

Three numbers have been quoted for how many contexts that is — 33 in #303's
commit message and the pom comment, "approximately 30" in #305's inventory — and
both were counted from annotations. Asked with the framework's own machinery, the
answer on `main` is **36**.

## Goals / Non-Goals

**Goals:**

- Make the count measurable, on demand, cheaply, and make it fail when it grows.
- Remove the property sets that fork a context for a difference nothing asserts
  on.
- Adopt `RestTestClient` where the suite had hand-rolled what it provides.
- Change no production code and no requirement.

**Non-Goals:**

- Slicing anything. That is the large win and it needs its own change (or
  several), with the `@AutoConfigureTestDatabase(replace = NONE)` pattern
  reviewed once before it is applied fifteen times.
- Touching `spring.test.context.cache.maxSize`.
- Touching the repository-layer tests (#314 in flight), the packaging tests
  (#310 in flight) or the webhook tests (#121 in flight).

## Decisions

### 1. Count the cache key, not the annotations

`ContextBudgetTests` discovers every non-abstract class under `dev.skillsgateway`
carrying `@SpringBootTest` anywhere in its type hierarchy, and for each calls
`BootstrapUtils.resolveTestContextBootstrapper(type).buildMergedContextConfiguration()`.
That is the exact call `DefaultTestContext` makes to compute the cache key, and
it loads no context — the whole test runs in well under a second and starts
nothing. Distinct configurations go into a set; the size is the number of
contexts the suite will build with an unbounded cache.

**Why not parse Spring's cache statistics instead.** `DefaultContextCache` logs
`hitCount`/`missCount` at DEBUG, and `missCount` is a genuinely useful number —
it is reported in this change's evidence — but it is a *result* of a full run,
it conflates first builds with post-eviction rebuilds, and it cannot fail a build
before the run it describes. The budget has to be knowable before the suite
starts, or it is not a gate.

**Why a ratchet rather than a target.** The property that broke in #302 was
unbounded growth, not any particular number. A constant that must be edited, in a
diff, with a reason, is the cheapest thing that makes growth visible at the
moment it happens.

### 2. Collapse a property set only where nothing can observe the difference

Four collapses, and the argument for each:

| Before | After | Why it is not observable |
| --- | --- | --- |
| `MachineCredentialAdminTests` declared `skills-gateway.roles.admins=owner` **twice** | once | Identical strings; Spring compares the array, so `["a", "a"]` and `["a"]` were two cache keys for one configuration. Merges with `MachineRoleIntersectionTests`. |
| `RefAdvertisementTests` declared `skills-gateway.catalog.name=catalog` | nothing | `SkillsGatewayProperties.Catalog` substitutes exactly `catalog` for an unset name. The property set was equal to the base context's and forked one anyway. |
| `AdoptionTests`, `FourEyesTests`, `MachineApiScopeTests`, `MachineCredentialAdminTests`, `MachineCredentialLifecycleTests`, `MachineRoleIntersectionTests` each declared their own `skills-gateway.roles.admins` | one shared `AbstractNamedAdminsTest` naming the union | Each declared it only because authorization is always enforced (GW_AUTH_0025 — Authorization on the web surface is always enforced) and a gateway with no administrator refuses to start (GW_AUTH_0026 — A gateway with no administrator refuses to start). Which *names* are in the list is not something any of their assertions reads. |

**The risk that had to be checked, and how.** Because Arconia gives each context
its own database, contexts were also the suite's only isolation between classes
— there is no truncation, no `@Sql`, no `@DirtiesContext` anywhere in
`src/test`. Collapsing contexts therefore collapses databases, and that, not the
administrator list, is the real hazard. Every assertion in the six merged classes
was read for it. They fall into three groups: filtered to a fixture the suite
named itself (`uniqueName(...)`), tolerant by construction
(`isGreaterThanOrEqualTo`, `contains`, `anySatisfy`), or about a static table
rather than the database (`MachineApiRegistry`). None reads a global count.

**What was deliberately left out of the union**, and why it matters more than
what went in: three suites in the package assert that a named principal holds no
role *at all* — `dev` (`EscapeHatchRoleNegativeTests`), `mallory`
(`RoleEnforcementTests`) and `just-logged-in` (`MachineCredentialAdminTests`).
Each of those assertions is a tautology the moment its principal becomes an
administrator, so the union names none of them, and `AbstractNamedAdminsTest`'s
javadoc says so where the next person to add a name will read it.

`RoleEnforcementTests` keeps its own context although its property set was
identical to `MachineCredentialLifecycleTests`'. Its job is the deny-by-default
walk over every mutating route, which is only honest in a context whose
administrator list it controls entirely. It now pays for a context alone, which
is the correct price for that.

### 3. What was examined and deliberately not collapsed

- **`ExternalSourceResolutionTests` vs `AbstractExternalSourceTest`.** The
  subclass adds three ingestion budget caps to the base's four properties, and
  hoisting them would merge three classes into one context. The comment on those
  caps says they are *"small enough that the fixture can exceed them"* — the
  suite exceeds them on purpose. Applying them to the closure suites would make
  their fixtures subject to a cap chosen to be breached. The difference is
  load-bearing; it stays.
- **`CatalogTests`.** Its `catalog.name=catalog` is the same no-op as
  `RefAdvertisementTests`', but its first test asserts that an empty estate
  serves an empty catalog, over a rebuild composed from every approved snapshot
  in its context's database. It needs a database nothing else has approved into,
  so it keeps a context of its own and the no-op property stays as the thing that
  gives it one.
- **The six `ForwardedHeaders*` postures.** #305 is explicit that a test which
  exists to catch a JVM-versus-native divergence keeps a real context. Two of
  them look redundant beside each other — `unset` and `none` — and their
  *agreement* is the assertion: it is what separates "the strategy was read and
  said no" from "nothing ever read the strategy".
- **Every remaining property set.** Each configures something an assertion in
  its suite reads: a mirror that cannot be pushed to, a TTL cap, a conformance
  mode, a claim mapping, an estate that fails to start.

### 4. `RestTestClient`

`AbstractForwardedHeadersTest` built a `RestClient` with an explicit
`JdkClientHttpRequestFactory`, called `retrieve().toBodilessEntity()`, then
asserted the status and pulled `Location` off the entity by hand.
`RestTestClient.bindToServer(...)` is that, with the assertions built in. The
request factory stays explicitly named: the JDK client never follows a redirect,
and the redirect is the response under test, so a factory chosen by detection
would silently turn every assertion in the suite into an assertion about the
identity provider's error page.

There is no `@AutoConfigureRestTestClient` in Spring Boot 4.1.1 — the annotation
#305 names does not exist in this stack — and there is no `TestRestTemplate`
anywhere in the repository to sweep. `bindToServer` is the whole of the
available adoption, and this change takes it.

## Risks / Trade-offs

- **Six suites now share a database that each had to itself.** Mitigated by
  reading every assertion in them (above) and by the full suite passing, but it
  is a real reduction in isolation and worth naming rather than burying. The
  alternative — keeping a context per suite — is what #302 was.
- **The budget is a constant someone will raise without thinking.** Mitigated by
  the failure message, which names the property sets and asks the three questions
  worth asking before editing the number, and by the same three questions in
  `guides/local-development.md`.
- **`BootstrapUtils.buildMergedContextConfiguration()` is framework-internal in
  spirit even though it is public.** If a future Spring release changes it, the
  budget test breaks loudly at compile or run time rather than silently
  miscounting, which is the acceptable failure direction.

## Migration Plan

None. No production code, no schema, no API, no configuration.
