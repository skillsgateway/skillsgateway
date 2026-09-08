> **Re-validated against `main` at implementation time (2026-09-08).** Five facts
> moved since this was written; each is corrected in place below and in design.md,
> and the route is unchanged. In summary: the requirement ids GW_VETTING_0025/GW_VETTING_0026 were
> taken by another change and became **GW_API_0005/GW_API_0006**; `WebhookEvent.ALL` now
> holds **nine** events, not eight; a **second** payload shape
> (`ApprovalPendingPayload`, GW_WEBHOOK_0006/GW_WEBHOOK_0007) exists and already carries
> `requiredMode = REQUIRED`; `.oasdiff.yaml` with a `severity-levels` file now
> exists on `main` (#216), which retires one of design.md's rejected alternatives
> as written; and **two pre-release tags now exist** (`0.2.0-b1`, `0.2.0-b2`), so
> the premise "no tags and no releases" is no longer literally true — see
> design.md, "The events endpoint changes shape rather than gaining a sibling".

## Why

The REST half of issue #121 shipped (archived as
`2026-08-24-add-api-compatibility-gates`): the compatibility promise is written
down, the published `openapi.json` is checked against the document the build
serves, and a pull request whose contract diff is breaking is refused unless it
is declared. The webhook event payload surface got none of that. Nine lifecycle
events ship a signed JSON body to operator-registered receivers, and that body is
described **nowhere outside the Java record that produces it** —
`WebhookService.EventPayload` and `WebhookService.ApprovalPendingPayload`. Renaming `sha` to `commit` breaks every receiver
exactly as hard as renaming a REST field, and passes every gate this repository
runs, because there is no document to diff.

Now, for the same reason the REST half landed now: the only tags are two
pre-releases on the `0.x` line (`0.2.0-b1`, `0.2.0-b2`), both excluded from
`/releases/latest`, on a line the releasing guide says does not declare API
stability — so the payload has no deployed receivers and breaking it is still
about as cheap as it will ever be.
`docs/manual/reference/compatibility.md` promises additivity for `/api/**`
and says nothing at all about webhooks.

## What Changes

- **The payload surface becomes part of the contract document.** The served
  OpenAPI document (already `openapi: 3.1.0`, springdoc 3.1.0) gains a top-level
  `webhooks` object with one entry per name in `WebhookEvent.ALL`, each describing
  the delivery the gateway sends: the four `X-Skills-Gateway-*` headers, the
  `application/json` body, and the payload that event actually carries
  (`ApprovalPendingPayload` for `snapshot.approval_pending`, `EventPayload` for
  the other eight) as a component schema. The entries
  are generated from `WebhookEvent.ALL` by an `OpenApiCustomizer`, so an event
  added to the registry cannot fail to appear in the contract.
- **`GET /api/webhooks/events` returns the payload shape, not only the event
  names.** It becomes a typed record — `EventRegistry`: the event names, plus one
  worked example of each of the two delivery bodies — instead of a bare
  `List<String>`. This is a better endpoint — a subscriber discovers what it will
  receive, not just what it may filter on — and it is also what gives the gate its
  correct polarity (design — Decisions: oasdiff classifies a removed *response*
  property as an error and a removed *request* property only as a warning, and a
  webhook body is a response in everything but transport). **BREAKING** for the
  portal's generated types; additive on the wire only if the array stays. See
  Impact.
- **The payload's fields are marked required** in the schema, because they always
  are. That is what turns a removed field into `response-required-property-removed`
  (an oasdiff error) rather than a warning. `ApprovalPendingPayload` already does
  this; `EventPayload` did not. Since #216 the repository also raises
  `response-optional-property-removed` to an error, so this is now belt *and*
  braces rather than the only thing standing between a renamed field and a green
  gate — but it is the half that makes the published document honest.
- **The existing gate covers all of it, unchanged.** No new workflow, no new
  format, no new dependency, no new CLI. `api-contract.yml` already diffs
  `src/main/frontend/openapi.json` against the fork point with oasdiff and already
  demands the `⚠️ BREAKING CONTRACT` label plus a breaking PR title. Verified
  empirically that oasdiff v1.29.1 — the version the pinned action then ran —
  traverses the 3.1 `webhooks` object; see design.md. (The action has since moved
  to v0.1.14 on `main`; the traversal result is a property of the oasdiff binary,
  not of the action wrapper.)
- **The promise is written down.** `docs/manual/reference/compatibility.md` extends
  "The API contract" to the payload surface; `docs/manual/guides/lifecycle-webhooks.md`
  points receivers at the published shape and states the additive rule they may
  rely on.

Not in this change, deliberately:

- **AsyncAPI.** It is the route #121 floated first. It would publish a second
  document in a format this repository does not use, add a CLI and a lint step,
  and force a decision about that CLI's third `unclassified` bucket — to buy a
  contract the existing, already-gated document can carry. Rejected in design.md
  with the trade written out.
- **Generated JSON Schemas.** No generator on the classpath, and it buys a gate
  with no human-readable published contract — the weaker of #121's two routes.
- **`skills-gateway.estate.*` as a contract.** #121 asks in passing. Out of scope
  with a reason (design — Decisions).
- **Inbound webhook verification** (GW_INGEST_0012) and the signature *algorithm*. The
  headers are described; the HMAC construction stays prose in the guide, because a
  contract document can say a header exists but not how to compute it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `lifecycle-webhooks`: two new requirements — GW_API_0005 — The lifecycle event
  deliveries are part of the published contract document; and GW_API_0006 — Breaking
  changes to the lifecycle event payload surface are detected and declared.

## Impact

- **Requirements**: `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` gain GW_API_0005/GW_API_0006 and
  SVC_GW_API_0005/SVC_GW_API_0006. GW_VETTING_0025/GW_VETTING_0026 as originally proposed were taken while
  this sat unmerged — they are now external-vetting-connector requirements on
  `main`, which is exactly the silent SSOT collision the original id gap was meant
  to avoid. GW_FACADE_0023 is the highest committed; GW_0173–GW_0180 are left to changes
  in flight.
- **Java**: `WebhookService.EventPayload` gains `requiredMode = REQUIRED` on its
  seven components; a new `OpenApiCustomizer` (alongside the existing
  `documentVersion` bean in `api/OpenAPI.java`) builds the `webhooks` map from
  `WebhookEvent.ALL`; `WebhookController.events` returns the new `EventRegistry`
  record instead of `List<String>`.
- **API**: `GET /api/webhooks/events` response shape changes from a JSON array to
  an object. On the wire this **is** a breaking REST change, so this PR carries
  the `⚠️ BREAKING CONTRACT` label and a breaking title — the gate it is extending
  gets exercised on its own change, which is the best possible first test of it.
  See design — Open Questions for the alternative (a second endpoint) if the owner
  prefers not to break it.
- **Frontend**: `src/main/frontend/openapi.json` and `src/api/types.gen.ts`
  regenerated; any portal caller of the events endpoint and its MSW mock updated.
- **Tests**: `OpenApiContractTests` gains assertions that every `WebhookEvent.ALL`
  entry appears under `webhooks` and nothing else does, that each entry references
  the body its event actually carries, that both payloads are component schemas
  with their fields required, that the headers are described, and that a really
  emitted delivery carries every field the document promises — each with a
  negative counterpart in the style of `theStalenessCheckCanActuallyFail`. Two
  existing SVC tests in `WebhookTests` read the events endpoint as a bare array
  and move to `$.events`; their assertions are unchanged.
- **CI**: no workflow change. `api-contract.yml` is unmodified; the point of the
  design is that it already does the work.
- **Docs**: `docs/manual/reference/compatibility.md`,
  `docs/manual/guides/lifecycle-webhooks.md`,
  `docs/manual/reference/api/index.md`.
