# Design: marketplace-name-rule

## Context

See proposal.md — Why. The rule `^[a-z0-9][a-z0-9_-]*$` is written out four
times in the backend: `MarketplaceRegistrationService.MARKETPLACE_NAME`
(registration, and through it the estate reconciler), `GitFacadeConfiguration`
(fetch, and `HeldContentController` through it), `GitPublishConfiguration`
(hosted push/clone), and the `CHECK` on `marketplaces.name` in `V1__init.sql`.
The portal mirrors it in `form-rules.ts`, where one `GATEWAY_NAME` pattern is
shared by marketplaces, webhook subscribers and audit sinks.

## Goals / Non-Goals

Goals: one bounded rule, enforced identically at every place the old rule was;
a refusal that names the reason.

Non-goals:

- Webhook subscriber, audit sink and policy rule names. They share the
  character set but are not facade path segments; bounding them would be new
  scope with no reported need.
- The virtual catalog's configured name (`skills-gateway.catalog.name`). It has
  never been validated against the character rule either; that gap predates this
  change and is left alone.
- Plugin names in `ManifestRewriter` — a different identifier with the same
  shape.
- A display name. The owner decided the name stays the single identifier.

## Decisions

- **One Java definition, `persistence.MarketplaceName`.** It holds the bound
  (`MAX_LENGTH = 63`), the pattern built from it, the reason text, and
  `isValid`. Placed beside `Marketplace` because both the admin and facade
  packages already depend on `persistence`. The four copies become references
  to it. Alternative — keep the copies and add `{0,62}` to each — keeps the
  drift risk the facade comment in `GitPublishConfiguration` already worries
  about.
- **The bound is in the pattern, not a separate length check.**
  `^[a-z0-9][a-z0-9_-]{0,62}$` rejects in one test and is the same string the
  OpenAPI `pattern` and the database `CHECK` carry, so the three agree by
  inspection. The OpenAPI schema also states `maxLength: 63` for readers.
- **The message is the rule in words, not the regex.** "name must be 1 to 63
  lowercase letters, digits, - or _, starting with a letter or digit, because it
  is the /git/<name> path your clients clone". The portal's field error uses the
  same wording, and the persistent hint states the same facts plus the reason.
- **The estate needs no code of its own.** Declarations call the same
  `register()`; the failure reason is the 422 reason, recorded on the report and
  the ledger as every other entry failure is.
- **The facade answers not-found, as before.** A name over the bound is
  indistinguishable from an unknown one on `/git/**` and `/publish/**`, and a
  malformed holding on the revocation-status check stays 400 — no new status.
- **The portal gets its own marketplace rule.** `MARKETPLACE_NAME` and
  `MARKETPLACE_NAME_HINT` join `form-rules.ts`; `GATEWAY_NAME` stays for
  webhooks and sinks, whose server rule is unchanged.
- **Breaking, declared.** Narrowing a request field is a break under "The API
  contract"; the PR title carries `!`. Pre-1.0, the path prefix does not move.

## Risks / Trade-offs

- [A deployment already holds a longer name] → Pre-1.0, `V1` is edited in place
  and every database is recreated; there are no rows to migrate.
- [The facade negative test cannot fail before the change] → No row with a
  longer name can exist once registration and the CHECK refuse it, so the
  facade's own test pins the shared rule rather than a reachable exploit; the
  evidence says so.

## Migration Plan

None beyond recreating local databases, as for any `V1` edit.
