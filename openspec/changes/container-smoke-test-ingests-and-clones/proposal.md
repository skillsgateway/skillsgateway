# Proposal: container-smoke-test-ingests-and-clones

## Why

The container smoke test in `native.yml` asserts that the image boots, that
`/actuator/health` reports `UP`, and that one forwarded-header redirect is
honoured. It never asks the gateway to do anything.

That is how [#293](https://github.com/skillsgateway/skillsgateway/issues/293)
reached a release: every ingest returned 500 on the published image — the
gateway's core function — and the smoke test passed. The JVM suite was green
throughout both #272 and #293. Neither was a testing failure in the ordinary
sense; both were a failure to test *the artifact* rather than the code.

ADR 0012 drops the native image, which removes the *cause* of #293. It does not
remove the gap that let it ship: a required check that cannot see whether the
shipped artifact can do its job. A JVM container can be broken in other ways — a
missing resource, a bad merge, a configuration default — and this is the test
that would catch them.

## What Changes

One happy path through the four things the product is, run against the built
image before publication:

1. **Register** a marketplace from a fixture repository built in the job with
   plain git — no network dependency, nothing external to drift.
2. **Ingest** it, and insist the snapshot is `held` — not approved, and not
   rejected on a policy violation.
3. **Approve** it. Only `ApprovalService` publishes.
4. **Clone it back through the facade** with a PAT, and read the skill file out
   of the clone.

The health check and the forwarded-header assertion both stay. This adds a step;
it replaces nothing.

### The three decisions this needed

**The login is a real OIDC login, not the dev escape hatch.** The web surface
accepts nothing else, and `skills-gateway.dev-insecure-auth` has its own security
chain — a smoke test that only ever exercised the hatch would not notice the real
chain breaking. The job gains a mock provider service with `interactiveLogin`
false, unlike the portal e2e suite's, because there is no browser here: the
provider has to answer the authorization request with a code rather than a login
form. Its token callback sets an explicit subject, since a non-interactive token
otherwise carries none and an id_token with no `sub` is rejected.

**The fixture is bind-mounted and the `file` scheme is enabled.** The container's
root filesystem is read-only and its `/data` is a tmpfs, so a path on the runner
is not otherwise reachable from inside. `file` is off the default allowlist and
is enabled purely to deliver the fixture — the same thing the portal e2e suite
does. What is asserted is the registration, not the scheme.

**No new requirement or SVC.** Every one of the 218 SVCs is `automated-test`,
matched to JUnit results; a workflow shell step produces none the local reqstool
gate could see, so claiming one would either fail that gate or weaken what an SVC
means. This is a CI gate, like the health check beside it, and the existing smoke
assertions carry no SVC either.

## Impact

- `.github/workflows/native.yml` only. No production source, no test source, no
  API, no configuration surface, no documentation page describes the smoke test.
- Runtime cost: one extra service container and roughly fifteen seconds.
