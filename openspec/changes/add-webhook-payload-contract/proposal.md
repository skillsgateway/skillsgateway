## Why

The REST half of issue #121 shipped (archived as
`2026-08-24-add-api-compatibility-gates`): the compatibility promise is written
down, the published `openapi.json` is checked against the document the build
serves, and a pull request whose contract diff is breaking is refused unless it
is declared. The webhook event payload surface got none of that. Eight lifecycle
events ship a signed JSON body to operator-registered receivers, and that body is
described **nowhere outside the Java record that produces it** —
`WebhookService.EventPayload`. Renaming `sha` to `commit` breaks every receiver
exactly as hard as renaming a REST field, and passes every gate this repository
runs, because there is no document to diff.

Now, for the same reason the REST half landed now: there are no tags and no
releases, so the payload has no deployed receivers and breaking it costs nothing
today. `docs/manual/reference/compatibility.md` promises additivity for `/api/**`
and says nothing at all about webhooks.

## What Changes

- **The payload surface becomes part of the contract document.** The served
  OpenAPI document (already `openapi: 3.1.0`, springdoc 3.1.0) gains a top-level
  `webhooks` object with one entry per name in `WebhookEvent.ALL`, each describing
  the delivery the gateway sends: the four `X-Skills-Gateway-*` headers, the
  `application/json` body, and `EventPayload` as a component schema. The entries
  are generated from `WebhookEvent.ALL` by an `OpenApiCustomizer`, so an event
  added to the registry cannot fail to appear in the contract.
- **`GET /api/webhooks/events` returns the payload shape, not only the event
  names.** It becomes a typed record referencing `EventPayload` instead of a bare
  `List<String>`. This is a better endpoint — a subscriber discovers what it will
  receive, not just what it may filter on — and it is also what gives the gate its
  correct polarity (design — Decisions: oasdiff classifies a removed *response*
  property as an error and a removed *request* property only as a warning, and a
  webhook body is a response in everything but transport). **BREAKING** for the
  portal's generated types; additive on the wire only if the array stays. See
  Impact.
- **The payload's fields are marked required** in the schema, because they always
  are. That is what turns a removed field into `response-required-property-removed`
  (an oasdiff error) rather than a warning the gate ignores.
- **The existing gate covers all of it, unchanged.** No new workflow, no new
  format, no new dependency, no new CLI. `api-contract.yml` already diffs
  `src/main/frontend/openapi.json` against the fork point with oasdiff and already
  demands the `⚠️ BREAKING CONTRACT` label plus a breaking PR title. Verified
  empirically that oasdiff v1.29.1 — the version the pinned action runs —
  traverses the 3.1 `webhooks` object; see design.md.
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
- **Inbound webhook verification** (GW_0058) and the signature *algorithm*. The
  headers are described; the HMAC construction stays prose in the guide, because a
  contract document can say a header exists but not how to compute it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `lifecycle-webhooks`: two new requirements — the contract document describes
  every lifecycle event, its payload and its transport headers, generated from the
  same registry that produces the deliveries (GW_0145); and a breaking change to
  that payload surface is detected on a pull request and refused unless declared,
  on the same terms as the REST surface (GW_0146).

## Impact

- **Requirements**: `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` gain GW_0145/GW_0146 and SVC_GW_0145/SVC_GW_0146.
  Ids are deliberately **not** contiguous with GW_0131, the highest currently
  committed: two changes in flight on other branches are claiming GW_0132–GW_0137
  (`fix-discarded-ref-update-results`) and GW_0138 onward
  (`remove-roles-enabled-toggle`). Starting at GW_0145 leaves those room and
  avoids a merge collision in the SSOT, which is the one file where a collision is
  silent. GW_0132–GW_0144 stay unallocated by this change.
- **Java**: `WebhookService.EventPayload` gains `requiredMode = REQUIRED` on its
  components; a new `OpenApiCustomizer` (alongside the existing `documentVersion`
  bean in `api/OpenAPI.java`) builds the `webhooks` map from `WebhookEvent.ALL`;
  `WebhookController.events` returns a new record instead of `List<String>`.
- **API**: `GET /api/webhooks/events` response shape changes from a JSON array to
  an object. On the wire this **is** a breaking REST change, so this PR carries
  the `⚠️ BREAKING CONTRACT` label and a breaking title — the gate it is extending
  gets exercised on its own change, which is the best possible first test of it.
  See design — Open Questions for the alternative (a second endpoint) if the owner
  prefers not to break it.
- **Frontend**: `src/main/frontend/openapi.json` and `src/api/types.gen.ts`
  regenerated; any portal caller of the events endpoint and its MSW mock updated.
- **Tests**: `OpenApiContractTests` gains assertions that every `WebhookEvent.ALL`
  entry appears under `webhooks`, that `EventPayload` is a component schema with
  its fields required, and that the headers are described — each with a negative
  counterpart in the style of `theStalenessCheckCanActuallyFail`.
- **CI**: no workflow change. `api-contract.yml` is unmodified; the point of the
  design is that it already does the work.
- **Docs**: `docs/manual/reference/compatibility.md`,
  `docs/manual/guides/lifecycle-webhooks.md`,
  `docs/manual/reference/api/index.md`.
