# Design: refusals-are-problem-documents

## Context

See `proposal.md — Why`. Three facts, all established by running code rather than
reading it:

- A `ResponseStatusException` left MockMvc with **"Content type not set"** and an
  empty body. Not Boot's default error keys — nothing at all.
- The two `@ExceptionHandler` cases passed the same assertions before any fix,
  because they already return `ProblemDetail`. The inconsistency was between
  mechanisms, not across the whole API.
- `ProblemDetail` was absent from `components.schemas` entirely.

## Decisions

### 1. The fix is configuration, not a `@ControllerAdvice`

`spring.mvc.problemdetails.enabled: true` makes Boot's own exception resolver render
`ResponseStatusException` and its validation failures as problem documents. A
hand-written advice would duplicate what the framework already does and would have to
be kept in step with every exception type Boot learns to handle.

*Alternative considered:* a `@ControllerAdvice` mapping each exception explicitly.
Rejected — more code, and it would shadow the existing `@ExceptionHandler` methods
that are already correct and carry endpoint-specific reasons.

### 2. The document is derived, not annotated

96 responses needed re-pointing. Two ways to do it:

- **Annotate each** `@ApiResponse` with `content = @Content(schema = …)`. 96 edits, and
  the next endpoint's author has to remember the 97th. A forgotten one is invisible —
  springdoc silently substitutes the success schema and the document stays valid.
- **Walk the document once** in an `OpenApiCustomizer`. One place, cannot be forgotten,
  and a new endpoint is covered the moment it exists.

The second, for the same reason `lifecycleWebhookDeliveries` loops the catalogue
rather than carrying one annotation per event: drift you cannot see is worse than code
you have to read once.

What is *not* imposed is the `description`. Each `@ApiResponse` already says what its
particular refusal means — "the session holds no applicable role", "snapshot not
found" — and that is the half worth writing by hand.

### 3. The schema is declared rather than reflected

Nothing in the codebase returns `ProblemDetail` from a mapped controller method, so
springdoc has no signature to generate a component from. It is written out: `type`,
`title`, `status`, `detail`, `instance`, with `status` required.

Only `status` is required, and that is honest rather than lazy: `type` is
`about:blank` when the status is the whole story, and `detail` is present on every
refusal the gateway raises deliberately but not on one the container produces before
a handler runs.

## Risks / Trade-offs

- **A client parsing the old empty body** → there was nothing to parse; this can only
  add.
- **The customizer masks a genuinely different error schema** if some endpoint ever
  wants one → nothing does today, and a uniform envelope is the requirement. An
  endpoint that needs more should add fields to the problem document, which RFC 7807
  allows, not a second shape.
- **`instance` leaks the request path into the body** → the path is already known to
  the caller that sent it, and it is what makes a problem document useful in a log.
- **Boot's rendering of a container-level error is not covered** by this change's
  tests, only its own handling. A 404 for a path with no mapping is Boot's error page,
  not a `ResponseStatusException`; the contract speaks for documented endpoints.

## Migration Plan

None. No schema, no configuration an operator sets, no stored data. A deployment
picks the behaviour up by upgrading.
