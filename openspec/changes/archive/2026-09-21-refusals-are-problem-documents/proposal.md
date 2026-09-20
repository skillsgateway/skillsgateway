# Proposal: refusals-are-problem-documents

## Why

PR D of the 2026-09-20 1.0.0 readiness run — finding **2**, and the one finding the
review could not verify without running the gateway.

`docs/manual/reference/api/index.md` promised RFC 7807 `ProblemDetail` for every
error. Roughly 35 refusals throw `ResponseStatusException`, and there was no
`@ControllerAdvice` anywhere and no `spring.mvc.problemdetails` setting, so Boot did
not render them as problem documents.

**A test written before the fix established what actually happened, and it was worse
than inferred.** The review expected Boot's `{timestamp,status,error,path}` body with
the reason dropped. What the wire carried was **no content type and an empty body**.
The portal reads `body.detail` and falls back to `"409 Conflict"`, so a user saw a
bare status line and the reason a service took care to write never left the process.

The document half was independently wrong: springdoc gives a response with no
declared schema the operation's *success* schema, so **96 of 106 non-success
responses documented a refusal as returning the thing it refused to produce**.

Neither half had a requirement. The error envelope was observable behaviour that
nothing specified and no test read, which is how a document and an implementation
disagree for this long.

## What Changes

### 1. One line of configuration fixes the runtime

`spring.mvc.problemdetails.enabled: true`. The `@ExceptionHandler` methods already
returned `ProblemDetail`; this is what makes the other two routes to a refusal — a
`ResponseStatusException` and Boot's own validation handling — agree with them.

### 2. Every non-success response in the contract declares the shape

A third `OpenApiCustomizer` walks every operation and points each 4xx/5xx at a
`ProblemDetail` schema as `application/problem+json`, and registers that schema —
nothing returns the type from a mapped method, so springdoc had no signature to read
it from.

**Derived rather than annotated, deliberately.** Adding
`@ApiResponse(content = …)` to 96 declarations would be 96 chances to forget, and
forgetting is invisible: the document stays well-formed and simply lies. The
hand-written `description` on each `@ApiResponse` is kept — what a particular refusal
*means* is worth writing by hand; only the body shape is imposed.

### 3. The envelope becomes a requirement

`GW_API_0007 — Every refusal is a problem document carrying its reason`, with
`ErrorContractTests` as its verification. Four cases, each picking a refusal raised by
a different mechanism, because the property under test is that they agree.

## Capabilities

### New Capabilities

None — `admin-api` already exists and this is its error envelope.

### Modified Capabilities

- `admin-api`: gains `GW_API_0007`, the requirement that every refusal is an RFC 7807
  document carrying its reason, and that the published contract says so.

## Impact

- **API**: the *shape* of every error response changes from an empty body to a problem
  document. Additive in the sense that matters — nothing that was there is removed —
  but a client that was parsing the empty body was parsing nothing.
- **Backend**: one configuration line, one `OpenApiCustomizer`, one new test class.
- **Contract**: 106 non-success responses re-pointed; a `ProblemDetail` component
  added.
- **Docs**: `reference/api/index.md` now says `application/problem+json`, shows
  `instance`, and states that `detail` is the field to show a user.
- **Also corrected here**: the `@OpenAPIDefinition` prose in `api/OpenAPI.java` still
  quoted unversioned paths (`POST /api/marketplaces`). PR C's sweep matched mapping
  annotations, not free text inside the document's own description, so the published
  quick-start was left describing paths that no longer exist. Fixed with this change
  because this is the change that makes the document true.
- **The stop rule**: no configuration leaf (the property is Spring's, not
  `skills-gateway.*`), no package, no sweep, no estate object.
