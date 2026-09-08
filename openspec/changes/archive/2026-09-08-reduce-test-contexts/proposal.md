# Proposal: reduce-test-contexts

## Why

[#302](https://github.com/skillsgateway/skillsgateway/issues/302) was a suite
that had outgrown its shape: nearly every test class starts a full application
context, and the number of *distinct* contexts had grown until Spring's test
context cache stopped evicting. Every context built stayed resident, the heap
filled, and CI died with a partial test count and no error.

[#303](https://github.com/skillsgateway/skillsgateway/pull/303) bounded the
cache to 8. That made the suite survivable and is verified, but it is
containment, not a cure: the bound limits how many contexts are *held*, not how
many are *built*, and every eviction is paid back as a rebuild — in this project
a rebuild is also a fresh PostgreSQL container, because the Arconia dev service
registers its container per context.

[#305](https://github.com/skillsgateway/skillsgateway/issues/305) is the cure,
and says explicitly how to do it: *"Incremental, not a sweep: one area per
change, with the before/after numbers in the PR body."* This change is the first
increment, and it deliberately takes the two workstreams that touch no
production code and no other in-flight branch: the measurement, and the
property-set collapses the issue's own inventory named as having no behavioural
risk.

The measurement matters more than the collapses. Every number quoted about this
problem so far has been derived by counting annotations — #303's commit message
said 33 property sets, #305's inventory corrected that to "approximately 30" —
and both are wrong, because a context cache key is a `MergedContextConfiguration`,
not an annotation. Asked directly, the suite answers **36**. Nothing in the build
was counting, which is why unbounded growth was invisible until it was fatal.

**Observable behaviour of the gateway is unchanged.** No production source file
is touched. What the change buys is a smaller, cheaper, measured test suite and a
gate that notices the next time the count grows.

## What Changes

- **The count becomes a gate.** `ContextBudgetTests` resolves every Spring test
  class's `MergedContextConfiguration` — the framework's own cache key —
  *without loading a single context*, counts the distinct ones, writes the
  breakdown to `target/context-budget.txt`, and fails when the total crosses a
  budget constant. It is a ratchet: a new posture that genuinely needs its own
  context is a deliberate edit to one number, with the reason in the commit.
- **Property sets that differ by nothing observable are collapsed.** The
  inventory in #305 named these; this change removes them. The detail is in
  `design.md`, but the shape is: a property array whose two entries were the same
  string; a suite whose extra property was already implied by the base context;
  and a budget block that belonged on the shared base class rather than on one
  subclass.
- **`RestTestClient` replaces the hand-rolled client.**
  `AbstractForwardedHeadersTest` built a `RestClient` with an explicit
  `JdkClientHttpRequestFactory` to get a client that does not follow redirects.
  Spring Framework 7 ships `RestTestClient` for exactly this, and it carries the
  status and header assertions the suite was writing by hand. The request factory
  stays named — the redirect is the response under test — but the plumbing around
  it goes.
- **The pom comment stops quoting a number nobody measured.** The block that
  pins `spring.test.context.cache.maxSize=8` explains the bound in terms of the
  measured count and points at the test that now maintains it.

## Capabilities

### Modified Capabilities

_None._ No requirement changes. `GW_0163 — Proxy-reported scheme and host are
honoured only when configured, identically on every packaging` is verified by the
same six postures against the same real server; only the HTTP client that drives
them changes.

## Out of scope (named, so the boundary is explicit)

The remaining #305 workstreams, left for later increments and listed in
`design.md` with the reason each one was not taken here:

- **Slicing full-context tests** — `@WebMvcTest`, `@JdbcTest`,
  `@RestClientTest`, `@JsonTest`. The largest win by far, and the one that needs
  a reviewed pattern established once before it is applied fifteen times. The
  `@JdbcTest` group also collides with #314, in flight on the repository layer.
- **`@MockitoBean` / `@TestBean` collaborator overrides in place of a property
  set.** No remaining property set in the suite exists to swap a collaborator;
  every one of them configures behaviour. There is nothing here to convert until
  the slices above arrive.
- **Raising `spring.test.context.cache.maxSize`.** #305 says revisit it *once
  contexts are down*. After one increment they are not down far enough for the
  bound to stop mattering, and raising it on a measurement this change did not
  take would repeat the mistake that set it.
- **The six `ForwardedHeaders*` postures and the JVM-versus-native tests
  generally.** #305 is explicit that a test which exists to catch a
  JVM-versus-native divergence must keep starting a real context, and the two
  postures that look redundant beside each other (`unset` and `none`) are the
  pair whose *agreement* is the assertion.

## Impact

- **DB**: none.
- **Backend**: no production source file changes.
- **API**: none. No path, DTO or field changes, so the breaking-change gate has
  nothing to fail on.
- **Tests**: one new test class (`ContextBudgetTests`); edits to
  `AbstractForwardedHeadersTest`, `AbstractExternalSourceTest`,
  `ExternalSourceResolutionTests`, `MachineCredentialAdminTests` and
  `RefAdvertisementTests`. No `@SVCs` annotation is moved, weakened or removed.
- **Build**: `pom.xml` — comment only, on the surefire `cache.maxSize` block.
- **Docs** (same PR): `guides/local-development.md` gains the context budget
  beside the gates it runs with.
