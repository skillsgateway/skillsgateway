## Context

See proposal.md — Why. Issue #121; the REST half is archived as
`2026-08-24-add-api-compatibility-gates`, whose design.md this one deliberately
mirrors.

Facts established before designing, each checked against the tree rather than
recalled:

- The entire payload surface is **one record**:
  `WebhookService.EventPayload` — `event`, `occurredAt`, `marketplace`,
  `snapshotId` (`long`), `sha`, `state`, `actor`. All eight names in
  `WebhookEvent.ALL` ship this identical shape; only `event` varies. It already
  carries springdoc `@Schema` annotations.
- Transport, from `WebhookDispatcher` and `WebhookSigner`:
  `X-Skills-Gateway-Signature` (`sha256=<hex>`, HMAC-SHA256 over the exact body),
  `X-Skills-Gateway-Event`, `X-Skills-Gateway-Delivery` (the receiver's
  de-duplication key), `X-Skills-Gateway-Timestamp`; `Content-Type: application/json`.
- `EventPayload` is **absent** from `components.schemas` in the committed
  `src/main/frontend/openapi.json` — nothing references it from a path, so
  springdoc never emits it. The shape is documented nowhere but the Java.
- The served document declares `openapi: 3.1.0` and springdoc is 3.1.0, so the
  3.1 top-level `webhooks` object is available. `io.swagger.v3.oas.models.OpenAPI`
  exposes `getWebhooks`/`setWebhooks`/`addWebhooks` (swagger-models-jakarta
  2.2.52, verified by `javap`), and `@Webhook`/`@Webhooks` annotations exist too.
- AsyncAPI/springwolf: **zero** hits in the repository. No JSON-Schema generator
  on the classpath either. Both routes #121 floats are greenfield toolchains.
- The gate: `.github/workflows/api-contract.yml` diffs
  `src/main/frontend/openapi.json` against the PR's **fork point** using
  `oasdiff/oasdiff-action/breaking@2649ebe…` (v0.1.13) with `fail-on: ERR`, and
  requires a break to carry both the `⚠️ BREAKING CONTRACT` label and a
  breaking PR title. That action's `breaking/Dockerfile` pins
  `FROM tufin/oasdiff:v1.29.1` — the exact binary the verification below used.
- springdoc emits **no** `required` array for response records: of 64 committed
  schemas, exactly three have one, and all three are request DTOs.
- `OpenApiContractTests` asserts the committed document equals the served one and
  carries `theStalenessCheckCanActuallyFail`, a negative test proving the real
  assertion can go red. That is the pattern any new contract assertion here copies.
- `GET /api/webhooks/events` (`WebhookController`, GW_0088) returns a bare
  `List<String>`.

## Goals / Non-Goals

**Goals:**

- A rename or removal in the payload, or the disappearance of a lifecycle event,
  cannot merge silently — it fails, or it is declared in the string that will
  drive the version.
- Receivers get a **published, human-readable** contract, not only a gate. That
  was #121's stated reason for preferring AsyncAPI; it is achievable without it.
- The contract cannot drift from the code that produces the deliveries. It is
  generated from `WebhookEvent.ALL` and from the record itself, not hand-authored
  in parallel.
- Zero new dependency, zero new document format, zero new CLI, zero new workflow.

**Non-Goals:**

- Describing how the signature is *computed*. See Decisions.
- Versioning the payload independently of the API. The payload ships with the
  gateway; there is one version.
- Guarding `skills-gateway.estate.*`. See Decisions.
- Inbound webhook verification (GW_0058). Different direction, different surface.

## Decisions

### The payload goes in the OpenAPI 3.1 `webhooks` object, not AsyncAPI

Everything the AsyncAPI route buys — a published contract, per-event entries, a
schema receivers can read, a breaking-change gate — the existing document already
supports, and the existing gate already covers. AsyncAPI would add a second
document, a second format, an `asyncapi` CLI, a lint step, a second baseline to
extract in CI, and a decision about that CLI's third `unclassified` bucket. It
would also split the contract in two places, so "what does the gateway promise?"
would have two answers to keep in sync.

Generated JSON Schemas are the weaker of #121's two routes by its own account:
no generator is on the classpath, and it buys the gate without buying receivers
anything readable.

The `webhooks` object also renders in Scalar alongside the REST surface, so the
lifecycle events appear in the API reference an operator already reads.

Rejected: AsyncAPI. Cost above, benefit already available. If a *second*
transport ever appears (Kafka, AMQP), AsyncAPI becomes the right answer and this
decision should be revisited — an OpenAPI `webhooks` entry can only describe an
HTTP delivery.

### VERIFIED: oasdiff traverses `webhooks`, and the polarity is the interesting part

The whole design rests on oasdiff seeing inside `webhooks`. It does. Run against
the exact pinned binary, two hand-written 3.1 documents differing only inside a
`webhooks` entry:

```
$ podman run --rm -v <scratch>:/specs:ro docker.io/tufin/oasdiff:v1.29.1 \
    breaking /specs/base-webhooks.json /specs/rev-webhooks.json --fail-on ERR
3 changes: 2 error, 1 warning, 0 info
error	[new-required-request-property] at
	in API POST webhook:snapshot.approved
		added the new required request property `newlyRequired`
error	[request-property-type-changed] at
	in API POST webhook:snapshot.approved
		the `snapshotId` request property `type/format` changed from `integer/int64` to `string`
warning	[request-property-removed] at
	in API POST webhook:snapshot.approved
		removed the request property `actor`
EXIT=1
```

An identical control with the same operation moved into `paths` produced the same
three findings at the same three levels, so `webhooks` is a first-class citizen of
the diff, addressed as `API POST webhook:<event>`.

Removing a whole event is an error by default:

```
error	[webhook-removed]
	in components/webhooks
		webhook `snapshot.rejected` removed
```

**But the polarity is inverted for our purpose, and this is what shapes the rest
of the design.** A `webhooks` entry describes a request the *gateway sends*, so
oasdiff applies request-side severities:

| Change to the payload | Under `webhooks` (request side) | What it means for a receiver |
| --- | --- | --- |
| field removed / renamed | `request-property-removed` — **warning** | catastrophic |
| field type changed | `request-property-type-changed` — error | catastrophic |
| new required field added | `new-required-request-property` — error | harmless |
| whole event removed | `webhook-removed` — error | catastrophic |

With `fail-on: ERR` the single most likely break — a renamed field — would pass
the gate as a warning, and a purely additive field could fail it. A gate that is
green on the thing it exists to catch is worse than no gate.

### So the payload is *also* reachable from `paths`, as a response

`GET /api/webhooks/events` stops returning `List<String>` and returns a record
carrying both the event names and the payload schema. That makes `EventPayload`
reachable from `paths` as a **response**, where oasdiff's severities match the
direction the data actually flows:

```
$ podman run --rm ... breaking /specs/base-req.json /specs/rev-req.json --fail-on ERR
2 changes: 1 error, 1 warning, 0 info
error	[response-required-property-removed] at /specs/rev-req.json
	in API GET /api/webhooks/events
		removed the required property `payload/actor` from the response with the `200` status
warning	[request-property-removed] at
	in API POST webhook:snapshot.approved
		removed the request property `actor`
EXIT=1
```

The removal that was a warning is now an error, from the `paths` reference, with
no configuration at all. And the additive case stays clean — a new **optional**
payload field plus a brand-new webhook entry:

```
$ podman run --rm ... breaking /specs/base-req.json /specs/rev-additive.json --fail-on ERR
No breaking changes to report, but the specs are different.
EXIT=0
```

This is #121's fallback, adopted on its merits rather than as a fallback: an
endpoint that tells a subscriber *what it will receive* is strictly better than
one that lists filter strings, and it happens to be what makes the gate correct.

Both references are kept. They are complementary, not redundant:

| Caught by | Only there |
| --- | --- |
| `paths` response | field removed, field retyped, field made required — with response severities |
| `webhooks` entries | an event **name** disappearing (`webhook-removed`); the transport headers; the human-readable per-event contract |

### The payload's fields are marked required, and new ones are added optional

`response-required-property-removed` is an error; `response-optional-property-removed`
is only a warning (measured, same runs). Since springdoc emits no `required` array
for records by default, the seven components get
`@Schema(requiredMode = REQUIRED)`. This is not a trick to please the tool — every
one of them is always populated, so the document becomes more accurate at the same
time as the gate becomes correct.

The corollary is a rule worth stating in the guide: **a field added to the payload
later is added optional.** Two reasons. It is honest — a receiver cannot rely on a
field that only exists from release N onward. And it keeps the `webhooks`-side
`new-required-request-property` error from firing on a change that is genuinely
additive, which is the one false positive this design would otherwise carry.

Rejected alternative: promoting `request-property-removed` and
`response-optional-property-removed` to `err` in a checked-in `.oasdiff.yaml`
`severity-levels` file. It works — measured, both moved to error and the exit code
held at 1 — but it is a **global** severity change affecting the entire REST
surface, hidden in a config file, to compensate for a polarity mismatch that the
`paths` reference removes outright. The repository has no `.oasdiff.yaml` today
and the archived design was explicit that it is where a *recurring false positive*
goes, not a place to reshape policy. Keeping the default severities means the gate
still means what its documentation says it means.

Rejected alternative: an `err-ignore` file scoped to the webhook operations. Same
objection the archived design raised against ignore entries — a checked-in ignore
keeps suppressing its class of change in every later PR, and stays green long
after it stopped meaning anything.

### The `webhooks` entries are generated from `WebhookEvent.ALL`

An `OpenApiCustomizer` bean beside the existing `documentVersion` one in
`api/OpenAPI.java` loops `WebhookEvent.ALL` and calls `addWebhooks(name, pathItem)`
with a `PathItem` whose `POST` operation carries the four headers, the
`application/json` request body referencing `#/components/schemas/EventPayload`,
and a `2xx` response the receiver is expected to return.

Not the `@Webhook`/`@Webhooks` annotations, though they exist in
swagger-annotations-jakarta 2.2.52. Eight annotations would be eight copies of the
same shape, hand-maintained beside a registry that already enumerates the events —
exactly the drift the change exists to close. A loop over `ALL` makes it
structurally impossible to add an event without adding it to the contract, and a
test asserts that correspondence anyway.

`WebhookEvent.AUDIT_EXPORT` stays out, by the same construction that keeps it out
of `ALL`: it is provisioned by creating an audit export sink, never by subscribing.

### The four headers are described; the signature *algorithm* is not

The `webhooks` entries describe each `X-Skills-Gateway-*` header as a request
header with its description and, for `X-Skills-Gateway-Signature`, the
`sha256=<hex>` pattern. That is a contract statement: a header renamed or dropped
is a break, and the diff will say so.

How the HMAC is computed — over the exact received bytes, with the subscriber's
`whsec_` secret, compared in constant time — stays prose in
`docs/manual/guides/lifecycle-webhooks.md`, with its runnable verification
examples. A schema can say a header exists and constrain its shape; it cannot
express "this is an HMAC-SHA256 of the body under your secret", and pretending
otherwise in a `description` string would put a security-critical instruction
somewhere no reader looks and no test checks. The guide is the SSOT for the
scheme; the document is the SSOT for the wire shape. Compatibility.md will point
at both.

### `skills-gateway.estate.*` is out of scope — its own issue

#121 asks in passing. It is a real gap with the same property: a renamed key
breaks a checked-in estate file. But it is not the same *problem*:

- Nothing about this change reaches it. There is no OpenAPI representation of the
  estate schema, so oasdiff will never see it however this lands.
- Its baseline mechanism has to be different. The estate schema's shape lives in
  `SkillsGatewayProperties` and its bound types; a gate for it means either
  generating a JSON Schema of the configuration tree or asserting a checked-in
  fixture parses — a separate design with a separate document to keep current.
- It has a different blast radius and a different remedy. A broken estate file
  fails at startup, loudly, for the one operator who owns the file — not silently
  in a receiver at 3am.

Folding it in would make one change carry two unrelated designs and would delay
the webhook gate behind an undecided one. Recommendation: a follow-up issue,
referenced from #121 when it closes. Flagged in Open Questions as the owner's call.

### The events endpoint changes shape rather than gaining a sibling

`GET /api/webhooks/events` returning an object instead of an array is a breaking
REST change, and the PR therefore declares itself as one and carries the label.
That is deliberate: there are no releases and no external clients, the portal's
types are generated so the compiler finds every caller, and it means the gate
being extended is exercised on its own change — with the escape path walked once,
in the open, by the person who built it. The archived design called an unexercised
gate untrustworthy; this is the cheapest possible exercise of it.

The alternative — a new `GET /api/webhooks/events/payload` alongside the
unchanged array — is purely additive and would need no label. It is in Open
Questions, because it is the owner's call whether to spend a declared break here.
It leaves two endpoints answering one question, forever, to avoid breaking a
contract with no clients.

## Risks / Trade-offs

- **oasdiff changes how it classifies webhook findings in a later version** → the
  action is SHA-pinned and Renovate proposes bumps as PRs; a reclassification
  would surface as this repository's own gate going green or red unexpectedly on a
  bump PR. The `OpenApiContractTests` assertions about document *content* are
  independent of oasdiff, so the contract itself stays checked even if the diff
  tool's severities move.
- **The `paths` response and the `webhooks` entry describe the same schema and
  could disagree** → they cannot: both `$ref` the single generated
  `EventPayload` component, which comes from the one record the dispatcher
  serialises.
- **`new-required-request-property` fires on a genuinely additive field** → the
  stated rule (new payload fields are optional) prevents it. If it fires anyway,
  the label escape exists and the failure is legible; that is the moment a scoped
  `.oasdiff.yaml` entry would earn its keep, with a comment saying why.
- **Committed `openapi.json` grows by eight near-identical `webhooks` entries** →
  noise in the file, once. It is generated and key-sorted by `OpenApiSnapshot`, so
  it stays stable afterwards.
- **A receiver reads the document and assumes the `2xx` response shape is
  meaningful** → the entries declare only that a `2xx` is expected; the retry and
  backoff contract stays where it is documented, in the guide.
- **Declaring a break on this PR sets a precedent for cheap breaks** → mitigated
  by the same thing that mitigates it in general: it takes a label and a title,
  both permanent in the record, and both visible in review.

## Migration Plan

No runtime migration; no persisted state involved. Order within the PR:

1. `requiredMode = REQUIRED` on `EventPayload` and the typed events response, so
   `EventPayload` becomes a component reachable from `paths`.
2. The `webhooks` customizer.
3. Regenerate `openapi.json` and `types.gen.ts`; fix the portal callers and the
   MSW mock.
4. Contract tests, each with its negative counterpart.
5. Docs.
6. Label the PR `⚠️ BREAKING CONTRACT`, title it as a breaking change, and confirm
   the gate goes red without the label and green with it.

Rollback is a revert. The only externally visible change is the events endpoint's
response shape.

## Open Questions

- **Spend a declared break on `GET /api/webhooks/events`, or add a sibling
  endpoint?** The design assumes the break (rationale above). The owner may prefer
  the additive route; it changes tasks 3.x and the PR's label/title only, not the
  gate design.
- **`skills-gateway.estate.*`** — recommendation is a follow-up issue, not this
  change (rationale above). Owner's call before #121 is closed, since #121 lists
  it as open.
- **A second baseline at the first released tag.** Inherited unchanged from the
  archived design: when a tag exists, the same gate could also diff against the
  released document. Adding the webhook surface does not change that question.
