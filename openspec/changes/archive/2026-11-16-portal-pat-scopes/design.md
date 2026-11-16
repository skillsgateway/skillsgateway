# Design: portal-pat-scopes

## Context

- `POST /api/v1/tokens` takes `scopes` (fetch) and `pushScopes`. `TokenService.validateScopes`
  treats null/empty as *every marketplace*, and otherwise refuses any name that is not a
  registered marketplace or `skills-gateway.catalog.name` with a 422 whose text is
  `unknown scope '<x>': scopes name registered marketplaces or the catalog`.
- `TokenView` and `IssuedToken` already carry `scopes` and `pushScopes`; the portal never
  read them. No API gap for listing.
- The catalog's name is configurable (default `catalog`) and no read exposes it:
  `CatalogInfo` has `sha`, `generatedAt`, `constituents`, `collisions`.
- Git keeps one credential per gateway (the wizard already says so).
- `GET /api/v1/marketplaces` answers every signed-in session with every registered
  marketplace, whatever its roles.

## Goals / Non-Goals

**Goals:** a user can ask for a narrow token in the portal; the list shows reach; the
wizard's wording stays true; the cleartext is not retained.

**Non-Goals:** enforcement changes; new scope types; push scopes in the portal;
system access tokens (their fetch scope is unrelated and stays empty).

## Decisions (issue questions 1–6, settled from the code)

**D1. Default stays *every marketplace*, on the page and in the wizard.** Alternative
considered: default the wizard to its own marketplace. Rejected for now, for a reason
found in the wizard's own text: git stores one credential per gateway, so a token
limited to `a` is the credential git then offers for `b`, and an out-of-scope fetch
answers *exactly like a marketplace that does not exist* (`GW_AUTH_0006`). A user who
set up `a` and then `b` would meet a 404 that looks like a typo or an unserved
marketplace and that a good facade deliberately makes indistinguishable from one. A
narrower default is safer for a leaked token and wrong for the common two-marketplace
user; the safer path is to make the narrow choice one click away and to say, beside
it, what it costs. This is an owner decision and is flagged in the PR with this
recommendation.

**D2. The catalog's name comes from the served revision.** Options: hard-code
`catalog` (wrong on any deployment that renamed it, and the server would refuse it as
unknown, or worse accept a name that is a marketplace); a new route (adds surface);
add `name` to the existing `CatalogInfo`. Chosen: the last. It is one record
component, additive within the major, reachable by exactly the callers who can already
read the revision. The consequence is stated plainly: the catalog is offered only once
a revision is being served (`GET /api/v1/catalog` is 404 while disabled or not yet
generated). A token scoped to a catalog that serves nothing would fetch nothing, so
withholding the entry loses no working configuration. The picker marks the entry in
words, because it is not a narrow choice: the catalog is one repository vendoring every
served marketplace, so a catalog-scoped token reads all of their content through that one
address and cannot fetch a marketplace's own address.

**D3. The control.** `SegmentedGroup` (the shared two-way control) switches between
*Every marketplace* and *Chosen marketplaces*. The second reveals a filter input and a
bounded, scrollable checklist (`max-h`), each row a real checkbox with its marketplace
name; ticked names stay listed in a "Chosen" summary with a count and a *Clear*
control, so a selection made while the filter hides it is never invisible. This copes
with hundreds of marketplaces without a virtualised list: marketplaces are tens in
practice and the filter bounds what is read; a virtualised list would add a dependency
and a harder accessibility story for no observed need. A role-less user sees the same
list: everyone signed in can already read every marketplace, and a token only narrows.

**D4. Valid means named.** The submit stays disabled while the limited choice has no
name ticked, with a persistent hint bound by `aria-describedby` (design-conventions).
This is the one rule the client *must* mirror and cannot leave to the server: the server
accepts the empty list and means *all*. A name that vanishes between choice and
submit (a marketplace removed) is left to the server's 422, shown verbatim in the
`role="alert"` already beside the form. The client does not re-validate names against
its own list (the server registry is authoritative; the allowed exception in
design-conventions).

**D5. The list's *Reach* column** reads `token.scopes`: empty or absent is "every
marketplace" in muted text; otherwise the first three names as chips with "+N more"
whose `title` and screen-reader text hold the whole list. `pushScopes`, when non-empty,
adds "publishes to: …"; it never shows "none" (the common case would be noise). The
presentational `TokenTable` gets the column; a story shows all four kinds of row.

**D6. Push scopes are not offered in the portal.** They are role-gated per marketplace
(administrator or approver of that marketplace), so most people cannot use the control,
the portal would have to learn per-marketplace rights to hide it correctly, and a push
token is a credential for a publisher, not for a client. The API remains the home; the
list *shows* push scopes, so a token made there is not hidden. Owner decision, flagged.

**D7. Cleartext.** `create.reset()` runs in the success callback once the page/wizard
holds the value, as in `system-tokens.tsx`: a mounted observer keeps a mutation's
result, and `gcTime` only counts once it lets go. The page also keeps its existing
`create.isError` use: `reset()` in `onSuccess` does not touch a later error. Tests
inspect the query client's mutation cache while the component stays mounted.

**D8. Wizard wording** is computed from one function of the choice (`reachSentence`),
used before creation (what it will reach) and after (what it reaches), so the two
cannot drift. For the default it keeps the existing sentence verbatim, so
`SVC_GW_AUTH_0070` still verifies it unchanged.

**D9. Estate.** Personal tokens are API-managed runtime state (secrets) and stay
outside `skills-gateway.estate.*`, like PATs generally; nothing here adds an object.

**D10. Hardening from the `/impeccable audit` and `harden` pass** (the personal tokens page, the
wizard and the picker; the detector reported nothing, these are the verified gaps): the
"N chosen" line is a polite status so a screen reader hears each change; the list draws at
most 200 rows and says so (the filter reaches the rest), instead of a virtualised list (D3);
a chosen name the list no longer holds stays a row until unticked; a failed read of the
marketplaces is shown with **Try again**. `/impeccable critique` is for new pages and none is
added. Not done, with reasons: no debounce on the filter (it filters an in-memory list of
tens), no text-size or RTL work beyond `break-all` on names (marketplace names are a
restricted ASCII alphabet).

## Risks / Trade-offs

- A limited wizard token silently fails for the user's second marketplace (D1). Mitigated
  by saying so beside the choice and by keeping the default broad.
- The catalog entry only appears once a revision exists (D2).
- Marketplace names are shown as typed; they are already constrained to a safe
  alphabet by `MarketplaceName`.
