# Design: toggle-covers-external-connectors

## Decision 1 — widen the words, not narrow the code

Two ways close the gap between "built-in" in the prose and "every connector in
the chain" in the code. Narrowing the code would mean giving
`ConnectorToggleService` a second source of truth for what a connector is — a
hardcoded set of four names, or a marker on the built-in classes — so that a
name the chain demonstrably contains would be refused as unknown.

That loses something real. An operator's own connector is the one most likely
to be wrong for a single marketplace: a corporate scanner tuned for one
business unit, an LLM reviewer too slow for a large marketplace. Without the
switch, the only per-marketplace answer is a redeploy with a different
`skills-gateway.vetting.external[*]` list, which changes the chain identity for
every marketplace at once.

It also loses nothing on the security side, because the guarantees that make
the switch safe are properties of the *chain run*, not of which connector was
switched: `GW_VETTING_0029.2` records the disablement as its own verdict,
`GW_VETTING_0029.3` counts that verdict as neither clearing nor blocking so an
all-disabled run stays blocked, and `GW_VETTING_0029.4` makes every toggle
administrator-only and audited. Those hold identically for an external
connector — they never inspect the connector's kind.

So: the code is right, and four sentences are wrong.

## Decision 2 — where the test goes

The proof has to see an external connector and the switch in the same context.
`ConnectorToggleTests` has the switch and a real database but no external
connector, and it runs in the shared `AbstractGatewayTest` context, whose
`skills-gateway.vetting.external[*]` list is fixed for the whole suite.
`ExternalConnectorRegistrationTests` has the external connector already, in an
`ApplicationContextRunner` context — which `ContextBudgetTests` does not
count, because it is created and closed per test and never enters Spring's
test context cache.

So the test lands there, and the class's collaborators change from
constructor-local mocks to mock **beans**, so a test can stub and read them.
The switch itself is real: a genuine `ConnectorToggleService` over the same
`List<VettingConnector>` the chain runs, which is the whole point — a stubbed
toggle service would assert nothing about which names it accepts.

What stays mocked is persistence and notification. The rule under test
(per-marketplace, then global, then enabled) is resolved in the service from
two repository lookups; running it against real SQL would verify Flyway, not
the rule, and would cost the container this class was moved off in #305.

The run is a **re-vetting of an approved snapshot** rather than an ingestion.
Both execute the identical chain loop; re-vetting avoids the approval-pending
webhook announcement, which would need a `WaiverEvaluation.Effect` stubbed for
no assertion's benefit.

## Decision 3 — `DISABLED` is the evidence, not the absence of a call

The external connector under test points at `http://127.0.0.1:59321/vet`, where
nothing listens. `ExternalVettingConnector` is fail-closed, so a connector that
was *not* skipped would record `ERROR`, not silence. Asserting `DISABLED` is
therefore a positive assertion that the connector was never called, and it
would fail — not merely stop being interesting — if the toggle were narrowed
to built-ins.

## Decision 4 — the OpenSpec delta is a stub, as this capability's entries are

`openspec/specs/snapshot-vetting/spec.md` carries `GW_VETTING_0029.1` as an
id-only pointer ("The system SHALL implement GW_VETTING_0029.1"), the shape
every requirement in that file takes: `docs/reqstool/requirements.yml` is the
SSOT and the text lives there alone. The `MODIFIED` delta here restates that
pointer unchanged, which is all a delta can honestly say when the change is to
text OpenSpec deliberately does not hold.
