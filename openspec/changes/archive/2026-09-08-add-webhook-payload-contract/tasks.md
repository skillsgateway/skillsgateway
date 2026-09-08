## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_API_0005 (the contract document describes every lifecycle event, its
      payload and its transport headers, generated from the registry that produces
      the deliveries) and GW_API_0006 (a breaking change to the webhook event payload
      surface is detected on a pull request and refused unless declared) to
      `docs/reqstool/requirements.yml` — title, significance, description,
      rationale, categories, revision, in the style of the surrounding entries.
      Ids: GW_VETTING_0025/GW_VETTING_0026 were taken on main while this sat unmerged, so the change uses GW_API_0005/GW_API_0006; see proposal — Impact.
- [x] 1.2 Add SVC_GW_API_0005 and SVC_GW_API_0006 to
      `docs/reqstool/software_verification_cases.yml`, GIVEN/WHEN/THEN in the
      style of SVC_GW_API_0003/SVC_GW_API_0004.
- [x] 1.3 Confirm no other branch has since claimed GW_API_0005/GW_API_0006 (`grep`
      `docs/reqstool/requirements.yml` on `main` and on the in-flight branches
      named in the proposal) before writing them.

## 2. The payload becomes a schema reachable from `paths`

- [x] 2.1 `WebhookService.EventPayload`: add `requiredMode = Schema.RequiredMode.REQUIRED`
      to all seven components (`event`, `occurredAt`, `marketplace`, `snapshotId`,
      `sha`, `state`, `actor`) — they are always populated, and required-ness is
      what makes a removal an oasdiff error (design — Decisions). Add
      `@Requirements({"GW_API_0005"})`.
- [x] 2.2 Add a response record for the event registry (event names plus the
      payload schema) and change `WebhookController.events` to return it instead of
      `List<String>`. Keep the existing `@Requirements({"GW_WEBHOOK_0005"})` and add
      `GW_API_0005`; keep `WebhookEvent.AUDIT_EXPORT` out by construction. Update the
      `@Operation` description to say the payload shape is included.
- [x] 2.3 Confirm `EventPayload` now appears in `components.schemas` of the served
      document with a `required` array (it is absent today — nothing referenced it).

## 3. The `webhooks` object

- [x] 3.1 Add an `OpenApiCustomizer` bean beside `documentVersion` in
      `api/OpenAPI.java` that loops `WebhookEvent.ALL` and calls
      `addWebhooks(name, pathItem)`. Each `PathItem` gets a `POST` operation with
      the `application/json` request body `$ref`-ing
      `#/components/schemas/EventPayload` and a `2xx` response. Annotate
      `@Requirements({"GW_API_0005"})`.
- [x] 3.2 Describe the four transport headers on each operation as request headers
      with descriptions, and give `X-Skills-Gateway-Signature` the `sha256=<hex>`
      pattern. Source the header names from the `WebhookSigner` constants, never
      re-spelled as literals. Do **not** put the HMAC construction in the document
      (design — Decisions); the guide owns the scheme.
- [x] 3.3 Add a `Webhooks` `@Tag` description so the entries render with the rest
      of the surface in Scalar.

## 4. Regenerate the published contract

- [x] 4.1 `./mvnw test -Dtest=OpenApiDocsTests` then
      `cp target/openapi.json src/main/frontend/openapi.json`.
- [x] 4.2 `(cd src/main/frontend && pnpm exec openapi-typescript openapi.json -o src/api/types.gen.ts)`.
- [x] 4.3 Fix every portal caller of the events endpoint for the new response
      shape, and its MSW mock (ADR 0003: mocks are contract-derived, never
      hand-authored). `pnpm typecheck`.
- [x] 4.4 Verify the committed document now carries a top-level `webhooks` object
      with all eight names and an `EventPayload` component.

## 5. Tests — and prove each one can fail

- [x] 5.1 `OpenApiContractTests`: every name in `WebhookEvent.ALL` appears as a key
      of the served document's `webhooks` object, and nothing else does — so
      `AUDIT_EXPORT` cannot leak in and an added event cannot be forgotten.
      `@SVCs({"SVC_GW_API_0005"})`.
- [x] 5.2 `OpenApiContractTests`: `EventPayload` is a component schema whose
      `required` array names all seven fields, and whose `snapshotId` is
      `integer/int64` — the two properties the gate's error-level classifications
      depend on. Part of `SVC_GW_API_0005`.
- [x] 5.3 `OpenApiContractTests`: each `webhooks` operation describes all four
      `X-Skills-Gateway-*` headers, asserted against the `WebhookSigner` constants
      rather than string literals. Part of `SVC_GW_API_0005`.
- [x] 5.4 `OpenApiContractTests`: the events endpoint's `200` response schema
      reaches `EventPayload`, so the payload stays inside the `paths` surface the
      gate diffs with response severities. Part of `SVC_GW_API_0005`.
- [x] 5.5 **Negative counterparts**, in the style of
      `theStalenessCheckCanActuallyFail` — extract each assertion above into a
      private method and drive it from a mutated document, asserting it throws:
      an event dropped from `webhooks`, a field dropped from `required`, a header
      removed, the `EventPayload` reference removed from the response. A check
      nobody has seen fail is a check nobody should trust.
- [x] 5.6 `OpenApiContractTests`: extend
      `contractWorkflowCarriesTheBreakingChangeContract`, or add a sibling, so the
      workflow's coverage of the payload surface is asserted — that the diffed
      document is the one carrying `webhooks`, and that the label and title rules
      apply to it identically. `@SVCs({"SVC_GW_API_0006"})`.
- [x] 5.7 A dispatcher-side test that the serialised body actually carries every
      field the schema declares required — the contract is worthless if the
      document promises a field the wire omits. Part of `SVC_GW_API_0005` (it verifies
      the document, not the gate).
- [x] 5.8 Confirm no existing SVC test was weakened or deleted to make any of the
      above pass (CLAUDE.md).

## 6. Write the promise down

- [x] 6.1 `docs/manual/reference/compatibility.md` — extend "The API contract" so
      the promise covers the webhook payload surface: the events, the payload
      fields and the transport headers only grow within a major. Extend the
      additive/breaking table with payload rows, and state the rule that a payload
      field added later is added **optional** and why.
- [x] 6.2 `docs/manual/reference/compatibility.md` — "How it is enforced": say the
      same oasdiff gate covers the payload, name what it catches from each of the
      two references (`webhooks` → an event disappearing; the `paths` response →
      a field removed or retyped), and say plainly what it does not catch.
- [x] 6.3 `docs/manual/guides/lifecycle-webhooks.md` — point receivers at the
      published shape (the `webhooks` entries in the API reference and
      `GET /api/webhooks/events`), state the additive promise they may rely on, and
      keep the signature scheme where it is. Cross-link compatibility.md.
- [x] 6.4 `docs/manual/reference/api/index.md` — note that the reference now
      includes the webhook deliveries, not only the REST paths.
- [x] 6.5 No requirement text anywhere in `docs/manual/` (CLAUDE.md — reqstool is
      SSOT); `mkdocs build --strict`.

## 7. Exercise the gate on this change

- [x] 7.1 Label the PR `⚠️ BREAKING CONTRACT` and write a breaking conventional
      title — the events endpoint's response shape changes (design — Decisions).
      Unless the owner chose the additive sibling-endpoint route from design —
      Open Questions, in which case neither is needed.
- [x] 7.2 Confirm the **API contract** check goes red without the label and green
      with it. Do not trust an unexercised gate. (Run on the PR itself: the label
      is added only after the first red run, so both states are in the record.)
- [x] 7.3 Run the pinned oasdiff binary locally against the fork-point document
      with an entry deleted from `WebhookEvent.ALL`, and confirm it reports
      `webhook-removed` as an error. Recorded in evidence.md.
- [x] 7.4 Same, with one payload field renamed: confirm
      `response-required-property-removed` at error level — the case the
      `webhooks` reference alone would only warn about. Recorded in evidence.md.

## 8. Gates and close-out

- [x] 8.1 `./mvnw clean verify` (`clean` — the reqstool gate needs it; needs
      Docker/Podman).
- [x] 8.2 `(cd src/main/frontend && pnpm test:stories)`.
- [x] 8.3 `(cd src/main/frontend && pnpm e2e)`.
- [x] 8.4 `reqstool status local -p docs/reqstool` — must end `PASS`.
- [x] 8.5 `openspec validate --all --strict`.
- [x] 8.6 `mkdocs build --strict`.
- [x] 8.7 Write `openspec/changes/add-webhook-payload-contract/evidence.md`: the
      commands and pasted result tails of one fresh run of 8.1–8.6 after the last
      code edit, the oasdiff verification runs from 7.2–7.4, and the commit SHA.
- [x] 8.8 Open the PR with an **Evidence** section summarising 8.7, and resolve
      design — Open Questions with the owner in the PR body.
- [x] 8.9 Archive the change (`/opsx:archive`) as the final commit of the PR.
