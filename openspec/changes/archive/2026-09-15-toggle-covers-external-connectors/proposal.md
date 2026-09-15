# Proposal: toggle-covers-external-connectors

## Why

A question raised while filing
[#377](https://github.com/skillsgateway/skillsgateway/issues/377) — does the
administrative connector switch reach an operator's own connectors? — has an
answer in the code that none of the words agree with.

`GW_VETTING_0029.1` — *A connector's effective state resolves per-marketplace,
then global, then enabled* — says the switch disables "a specific **built-in**
vetting connector". The `@Operation` description on
`PUT /api/vetting/connectors/{name}/toggle` says built-in, the API reference
page says built-in and names the four of them, and the 422 message says "the
built-in connectors are …".

The code says otherwise, and always has:

- `ConnectorToggleService` builds its known-connector set from the injected
  `List<VettingConnector>`;
- `ExternalVettingConnectorRegistrar` registers every
  `skills-gateway.vetting.external[*]` entry as an `ExternalVettingConnector`
  bean, which implements `VettingConnector` and joins that same list;
- so an external connector's name is accepted by the toggle, and
  `VettingService.run` then skips it and records a `DISABLED` verdict for it
  exactly as it does for a built-in.

The gap matters in both directions. An operator reading the documentation
believes their LLM reviewer cannot be switched off per marketplace and
redeploys the gateway to achieve what one API call already does; a security
reviewer reading the same words believes the anti-bypass guarantees of
`GW_VETTING_0029` — *The connector on/off switch never becomes a blanket
approval* — are scoped to four connectors they can enumerate, when the switch
in fact governs the whole chain.

## What Changes

**The words change; the code does not.** The switch applies to every connector
in the chain, built-in or external — which is what it already did.

- **`GW_VETTING_0029.1`'s description** drops "built-in": the switch covers a
  specific vetting connector, built-in or operator-configured. Its id is
  unchanged; its revision is bumped.
- **`SVC_GW_VETTING_0029.1` and `SVC_GW_VETTING_0029.2`** widen the same way,
  and each now also covers the external case a test asserts.
- **The `@Operation` summary and description** on the toggle endpoint, and the
  `ToggleRequest` schema description, say connector rather than built-in
  connector. `openapi.json` and `src/api/types.gen.ts` are regenerated, since
  the operation description is part of the committed contract.
- **The 422 message** becomes "the configured connectors are …", which is what
  the set it prints has always been.
- **`docs/manual/reference/api/marketplaces.md`** and one sentence under
  *External connectors* in `docs/manual/concepts/vetting.md` say the same.
- **A test pins it.**
  `ExternalConnectorRegistrationTests.anExternalConnectorIsSubjectToTheSameSwitchAsABuiltIn`
  toggles the configured external connector off for a named marketplace, and
  asserts the chain run for that marketplace records a `DISABLED` verdict in
  its place. Nothing listens on the connector's configured URL, so a connector
  that had run would have produced `ERROR` — `DISABLED` is evidence it was
  never called.

**Also fixed, in the same area:** `ApprovalService`'s javadoc still says
"There is no blanket override", which `GW_VETTING_0028` — *Administrative
override of a blocked vetting outcome* — retired. The sentence now says what
is true: a **reviewer** has no override and must waive each blocking finding,
and an administrator's override is a separate, audited act.

## Capabilities

### Modified Capabilities

- `snapshot-vetting`: `GW_VETTING_0029.1` — *A connector's effective state
  resolves per-marketplace, then global, then enabled* — is restated to cover
  every connector in the chain. **No behaviour delta**: this is the behaviour
  that shipped.

## Compatibility

Additive wording. No path, field, status code or default changes, and no
request the gateway accepted before is refused now. Not a breaking change.

## Out of scope

- **Restricting the code to built-ins.** That would be the other way to close
  the gap, and it is the wrong one: an operator's own connector is exactly the
  kind that is too noisy for one marketplace, and the anti-bypass guarantees
  (`GW_VETTING_0029.2` – `.4`) already hold for it — a disabled external
  connector is recorded, counts as neither clearing nor blocking, and cannot
  make a run clear on its own.
- **A portal control for the switch.** It remains API-only, as it was.
