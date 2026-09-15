# Tasks: toggle-covers-external-connectors

## 1. Prove it

- [ ] 1.1 Add `ExternalConnectorRegistrationTests
      .anExternalConnectorIsSubjectToTheSameSwitchAsABuiltIn`: the switch
      accepts the configured external connector's name for a named
      marketplace, an unknown name is still refused with a message naming it,
      and the chain run for that marketplace records a `DISABLED` verdict in
      its place
- [ ] 1.2 Expose the class's collaborators as mock beans and wire a real
      `ConnectorToggleService` into the context, so the switch under test is
      the production one over the production connector list

## 2. Requirements (SSOT)

- [ ] 2.1 `GW_VETTING_0029.1` description: the switch covers a specific
      vetting connector, built-in or operator-configured; revision bumped, id
      unchanged
- [ ] 2.2 `SVC_GW_VETTING_0029.1` and `SVC_GW_VETTING_0029.2`: same widening,
      each naming the external case its annotated tests now assert; revisions
      bumped

## 3. The contract

- [ ] 3.1 `ConnectorToggleController`: `@Operation` summary and description on
      `PUT /vetting/connectors/{name}/toggle`, the `GET
      /vetting/connector-toggles` description and the `ToggleRequest` schema
      description say connector, not built-in connector
- [ ] 3.2 `ConnectorToggleService`: the 422 message says "the configured
      connectors are …"; class javadoc and `ConnectorToggle`'s javadoc follow
- [ ] 3.3 Regenerate `src/main/frontend/openapi.json` and
      `src/api/types.gen.ts` with `pnpm gen:api-types`

## 4. Documentation

- [ ] 4.1 `docs/manual/reference/api/marketplaces.md` — "Connector
      enable/disable": every connector in the chain, built-in or external
- [ ] 4.2 `docs/manual/concepts/vetting.md` — one sentence under *External
      connectors*

## 5. Stale javadoc in the same area

- [ ] 5.1 `ApprovalService`: "There is no blanket override" predates
      `GW_VETTING_0028`. A reviewer has no override and waives each blocking
      finding; an administrator's override is a separate audited act

## 6. Gates

- [ ] 6.1 Run the gates and record `evidence.md`
- [ ] 6.2 Archive this change as the PR's final commit
