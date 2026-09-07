# Design: warn-duplicate-upstream-url

## Context

`MarketplaceRegistrationService.register` is the one registration gate (GW_0001,
GW_0063, GW_0084): the API and the estate reconciler both go through it, and its
existing checks — name pattern, reserved name, URL scheme allowlist, name
conflict — all either pass or throw. Adding a check that must never throw is a
new shape for this method.

The portal already has a working piece of this (PR #227, issue #224): a
pre-submission client-side check in the register dialog, comparing the URL
being typed against the marketplace list the page already has loaded, gating
**Register** behind an acknowledgement. That check is correct as far as it
goes and is not being replaced — it catches the common case before a request
is even sent. Its limit is what it can see: the marketplaces this page loaded
at some point before now, not the ones that exist at the instant the request
lands on the server.

## Goals / Non-Goals

**Goals**

- The server detects a normalized-duplicate URL independently of any client,
  so the check applies to every caller — the portal, the estate reconciler, a
  script.
- Warn, never block: the property under test is that registration still
  succeeds.
- One normalization rule, stated once, that the client and the server agree on
  byte-for-byte.

**Non-Goals**

- Not a uniqueness constraint. Two marketplaces sharing a URL remains
  perfectly registrable; nothing here can turn into a 409 later.
- Not a change to what the portal's pre-submission dialog does. It keeps
  comparing against its own loaded list and gating on acknowledgement; this
  change only adds a second, later signal from the authoritative source.

## Decisions

1. **The response gets a new wrapper type, not a mutated `Marketplace`.**
   `AdminController.RegisteredMarketplace` duplicates `Marketplace`'s fields
   flat (the same shape `MarketplaceView` already uses alongside `Marketplace`
   for the same reason: a nested `{marketplace: {...}, warnings: [...]}` body
   would remove every existing top-level field as far as a diff tool is
   concerned — a breaking change under the API compatibility gate — where a
   flat record with one field added is purely additive.

   *Alternative rejected:* `@JsonUnwrapped` on an embedded `Marketplace` field.
   It would keep the JSON flat, but springdoc's OpenAPI generation for
   `@JsonUnwrapped` is inconsistent across versions, and the resulting schema
   is what the compatibility gate diffs — an accurate, explicit schema is
   worth the field duplication `MarketplaceView` already established as this
   codebase's answer to the same trade-off.

2. **`register(...)` returns a small `RegistrationOutcome(Marketplace,
   List<String> warnings)` record instead of a bare `Marketplace`.** The two
   existing overloads both change; the estate reconciler and the one test file
   that call the 3-argument overload all discard the return value already, so
   nothing but the controller has to change at any call site.

3. **The duplicate check sits in the service, using `MarketplaceRepository.list()`
   already exposed for `GET /marketplaces`.** No new repository method or SQL:
   the estate this check runs over is exactly the set already public through
   that endpoint, so there is nothing to fetch that a caller could not already
   see. Comparing in Java rather than in SQL keeps the normalization rule in
   one place (`CloneUrlNormalizer`) instead of a Postgres function that could
   drift from it.

4. **Normalization lowercases scheme and host, strips a trailing slash, strips
   a `.git` suffix — and leaves the path's case alone.** Case-insensitive
   hostnames are a DNS fact; a case-sensitive path is a GitHub/GitLab/etc.
   fact (the repository `Acme/Skills` and `acme/skills` are different
   repositories on every forge this gateway resolves forge metadata for).
   Folding the path would create false-positive warnings the operator cannot
   explain from the URLs they see.

   Order matters inside the strip: `…/repo.git/` has the trailing slash
   *after* the `.git` suffix, `…/repo/.git` has it *before*. Stripping `.git`
   before the slash misses the first shape (the earlier version of the
   portal's own `normalizeCloneUrl`, and my first draft of
   `CloneUrlNormalizer`, both had exactly this bug — caught by
   `CloneUrlNormalizerTests` before either shipped). The fix strips a trailing
   slash, then `.git`, then a trailing slash again, which covers both orders.

5. **The portal keeps its own check and adds the server's as a second,
   independent signal.** Removing the pre-submission dialog and relying only
   on the post-submission toast would trade an actionable "the button won't
   let me do this by accident" for a "you already did it, here's what
   happened" — strictly worse UX for the case the dialog already catches.
   Keeping both means the dialog catches the common case before a request is
   sent, and the toast catches what only the server could have seen: a
   marketplace registered by someone else moments ago, or a registration made
   through any client that never ran the portal's check at all.

## Risks / Trade-offs

- **Two normalization implementations that must agree.** Mitigated by
  identical test tables in `CloneUrlNormalizerTests` (Java) and
  `form-rules.test.ts` (TypeScript) — the same input list asserted against
  both, so a future edit to one that silently diverges from the other fails a
  test rather than shipping a portal warning the server would not have given,
  or vice versa.
- **`marketplaceRepository.list()` scans every marketplace on every
  registration.** Accepted: an estate's marketplace count is small (this is a
  gateway's declared, curated set, not a corpus), and the endpoint this reuses
  already returns the same list on every portal page load.
