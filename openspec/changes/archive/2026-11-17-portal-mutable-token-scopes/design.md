# Design: portal-mutable-token-scopes

## Context

- `POST /api/v1/tokens` takes `scopes`; `TokenService.validateScopes` treats null or empty
  as *every marketplace* and refuses any other name that is not a registered marketplace or
  `skills-gateway.catalog.name` with a 422. Columns are `TEXT[]`; the schema refuses an
  empty `scopes` array (NULL is the only spelling of "every") and an empty `api_scopes`
  array, and refuses a credential that is both session-derived and holding API scopes.
- A personal token is a row with `api_scopes IS NULL`; a machine credential is a row with
  `api_scopes IS NOT NULL`. `TokenRepository` already scopes every personal statement by
  `api_scopes IS NULL AND principal = :principal`, which is what makes a personal route
  unable to reach a machine credential (`GW_AUTH_0057`).
- The facade resolves the credential from its hash on every request
  (`findActiveByHash`), so a stored change is seen by the next request: nothing caches a
  token's scopes.
- Rotation reads the old row, revokes it with a boolean `UPDATE`, then creates the
  successor from the row it read earlier.
- Every `/api/v1/tokens/**` route is on the registry's unreachable list, and
  `MachineApiRegistryTests` fails the build for a mapped route that is on no list.
- Services here use `@Transactional` and call `AdminAuditLogger` inside it
  (`AutoApprovalSettingService`), so a change and its ledger entry commit together.

## Goals / Non-Goals

**Goals:** scopes editable on a live personal token (owner) and a live system token
(administrator); nothing else about the token moves; every edit is on the ledger with
before and after; the portal can do it.

**Non-Goals:** push scopes (not editable, not shown by the new routes, not touched);
extending or changing an expiry; renaming; editing a revoked or expired token; delta-style
"add/remove one scope" bodies; changing who can make a machine credential.

## Decisions

**D1. `PATCH` with a full replacement list.** `PATCH /api/v1/tokens/{id}` takes
`{scopes}`; `PATCH /api/v1/tokens/machine/{id}` takes `{apiScopes}`. PATCH, because the
resource is a token and only part of it changes; a full list rather than add/remove
operations, because creation already takes a list, validation is then one function, and a
concurrent pair of "add x" and "remove x" has no ambiguity to explain. The response is the
token's view (`TokenView`, `MachineCredentialView`), never a secret. Both are additive
within the major.

**D2. (Revised, see the last section.) Every marketplace is stated, never read from an empty
list.** The personal body takes either a non-empty `scopes` list or `allMarketplaces: true`,
whichever way the token was created. An empty list, a body with neither, a body with both and
`allMarketplaces: false` are each refused **400**, with a message naming the field to use.
Creation is unchanged (omitted or empty still means every marketplace there). The portal sends
`allMarketplaces: true` for its *Every marketplace* choice and a non-empty list otherwise
(`GW_AUTH_0088`), which is why `lib/token-scope.ts` gets `updateBodyFor` rather than reusing
`scopesFor`. On the machine route an empty list is 422, as at creation. *(As first built, an
empty personal list meant every marketplace, as at creation, and the portal sent `[]` for it.)*

**D3. Strict bodies: unknown fields are 400.** The request records carry
`@JsonIgnoreProperties(ignoreUnknown = false)`, so a body naming `pushScopes`, `apiScopes`
on the personal route, `scopes` on the machine route, `expiresAt` or `name` is refused
before the controller runs, with nothing changed. Ignoring would let a caller believe a grant
changed that did not. If this gateway's mapper turns out to ignore the annotation, the test
(`SVC_GW_AUTH_0107`) fails and the fix is to bind the body as a tree and refuse extra keys;
the observable contract (400, nothing changed) does not change.

**D4. Who may edit what.**

- *Personal:* the owner only, through the same owner scoping as rotate and revoke. Another
  user's id, a nonexistent id and a machine credential's id are all **404**,
  indistinguishable, so the route is no directory. No role is needed: the owner already
  chooses any registered marketplace at creation, so an edit grants nothing creation does
  not. A session-derived credential (`GET /tokens` lists them) is editable like any other
  personal token: only its fetch scope moves, its lifetime does not, and its owner could
  mint another with any scope anyway.
- *Machine:* `admin` role, **and no more than that**. Widening API scopes is the most
  dangerous dimension, so this was weighed rather than assumed. Provisioning a credential
  with every scope needs the administrator role alone, so a stricter bar on *editing* would
  be theatre: the administrator it stops mints a new one. What editing in place does add
  is that the **existing secret**, already in a pipeline and possibly copied, silently
  gains authority; minting a fresh credential hands the new authority to a new secret that
  only the administrator sees. Mitigations instead of a second role: the entry on the ledger
  names the actor and both lists (`GW_AUTH_0086`), the portal states who a widening
  empowers (`GW_AUTH_0089`), and the route is unreachable by any credential, so no
  pipeline can widen itself. A four-eyes rule for widening is not built; it is an
  owner-level question recorded in the PR (recommendation: not now).
- *Neither route is reachable by a credential.* Both are added to the registry's
  unreachable list (`patch(...)` helper added to the registry), and no scope can reach them.
  A session-derived credential is a facade credential and has no route to `/api/**`; the
  test presents one as a bearer to prove it.

**D5. Atomic, and rotation copies what was revoked.** The edit is one statement:

```sql
UPDATE access_tokens t SET scopes = :scopes::text[]
  FROM (SELECT id, scopes FROM access_tokens
         WHERE id = :id AND principal = :principal AND api_scopes IS NULL
           AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)
         FOR UPDATE) o
 WHERE t.id = o.id
RETURNING t.*, o.scopes AS previous_scopes
```

The `FOR UPDATE` subquery makes the row's liveness and its "before" the state at the moment
of the write, so (a) an edit that loses a race with a revoke or rotate matches no row and is
**409**, never silently applied to a dead token, and (b) two concurrent edits form a chain in
which one's `after` is the other's `before`, which is what the ledger needs. When no row
matches, a second read tells 404 (none, not the caller's, or a machine credential) from 409
(exists but not live). The machine statement is the same with `api_scopes IS NOT NULL`,
no principal condition, and sets `api_scopes`.

The race that matters is edit versus rotate. Today rotate reads the row, then revokes with a
boolean `UPDATE`, then copies from the row it read: an edit committing between the read and
the revoke is reported saved and lost from the successor. The fix is that `revoke` returns the
row it revoked (`UPDATE … SET revoked_at … RETURNING *`, evaluated after any concurrent edit's
lock is released), and the successor is built from that returned row. Both rotations
(personal and machine) change; the liveness pre-check stays for the 409 message. The test runs
an edit and a rotation of one token concurrently many times and asserts the invariant *if both
returned success, the successor carries the edited scopes*; it is watched failing against the
old copy-from-stale-read code before the fix goes in.

**D6. One audit entry per change, in the same transaction.** `TokenService.updateScopes` and
`updateApiScopes` are `@Transactional` and call `AdminAuditLogger` inside it, so there is no
change without an entry and no entry for a refused change. Events (a second event name is
the existing split between `token-*` and `machine-credential-*`):

| Event | Actor | Detail |
| --- | --- | --- |
| `token-scopes-changed` | the owner | `token {id} '{name}' scopes {before} -> {after}` |
| `machine-credential-scopes-changed` | the administrator | `credential {id} '{name}' principal={p} scopes {before} -> {after}` |

Lists print as `every marketplace` in words for a token that fetches them all (never an empty
array, and not the creation entry's `all`) and as the sorted names otherwise. A no-op edit (same list) is still recorded: the owner
asked for it, a conditional entry is one more branch that could be wrong, and the entry shows
`before` equal `after`.

**D7. Nothing else moves.** The statement sets one column. A test compares the whole row
(hash, expiry, name, principal, owner, push scopes, session mark, last used, created) before
and after. Rotation after an edit copies the current scopes (tested, per scope).

**D8. The portal.**

- *Personal list:* `TokenTable` gets `onEdit`; a live (not revoked, not expired) row has an
  **Edit reach** button (`aria-label="Edit reach of token {name}"`). It opens
  `EditTokenScopesDialog`, which holds a `MarketplaceScopePicker` initialised from the token
  (`every` for an empty list, else `chosen`). **Save** is disabled while the choice is
  invalid (limited, nothing named), unchanged, or in flight, with a persistent hint saying
  which. When the new choice widens (to every marketplace, or adds a name), the dialog says
  so: the secret already in use fetches those marketplaces at once. The API's refusal (422 for
  a name, 409 for a token that died meanwhile, 404) shows as the API states it in a
  `role="alert"`. On success the `["tokens"]` query is invalidated, the dialog closes and a toast
  confirms; the row then shows the new reach.
- *System list:* the scope checklist is extracted from the create form into
  `ApiScopeChecklist` and used by both. `SystemTokenTable` gets `onEdit` and an **Edit scopes**
  button on live rows; the dialog starts from the token's scopes; Save is disabled with
  nothing ticked ("a token must hold at least one scope; to remove them all, revoke it"),
  unchanged or in flight; added scopes are listed in a warning saying that whoever holds the
  existing secret gets them at once. Refusals as stated; `["machine-tokens"]` invalidated.
- The edit dialog never receives or shows a secret. No `create`-style mutation result holds
  cleartext, so `GW_AUTH_0081` and `GW_AUTH_0073` are unaffected; a test asserts the update
  mutation's record holds none.

**D9. No migration.** `scopes` and `api_scopes` exist, are `TEXT[]`, and the constraints that
matter (empty arrays refused, session-derived credentials hold no API scope, a machine credential
has an expiry) already live in `V1`. An update only changes a value a row may already hold.
The `CHECK` is the backstop for the one invariant a bug could break, an empty `api_scopes`.

**D10. Estate.** Tokens are API-managed runtime state (secrets), outside `skills-gateway.estate.*`
like PATs generally. The change adds no object type and no grantable role.

**D11. Impeccable.** Audit and harden run on both pages (`/impeccable critique` is for new pages;
none is added). The detector reported nothing on the five files. Verified gaps, fixed: the widening
warning is a polite `role="status"` so a screen reader hears it appear; **Cancel** is disabled while a
save is in flight, so the dialog is not dismissed under a request that will still land. Dismissed, with
reasons: no mobile re-layout of the actions cell (the table already scrolls horizontally inside its
container, and the buttons are the page's existing `sm` size); no debounce or virtualisation (the picker
caps its rows and filters an in-memory list, D10 of the earlier change); no RTL or long-translation work
beyond `wrap-anywhere` on the dialog title (the portal is English-only and names are restricted).

**D12. Operation ids (revised: not pinned).** Adding two operations reshuffled springdoc's numbering of
the overloaded `revoke` handlers in `openapi.json` (`revoke_3` became `revoke_2`, `revoke_2` became
`revoke_1`, `revoke_1` became `revoke_3`). They were first pinned to their published values; the
owner decided to let them renumber. oasdiff classifies each as info (`api-operation-id-removed`), so
the gate passes; nothing in the repository names an operation id outside the generated types.

## Failure model (old-coder, tier 3)

| Way this can hurt | Defence | Test |
| --- | --- | --- |
| A caller edits a token that is not theirs | owner-scoped statement, 404 | `SVC_GW_AUTH_0103` |
| A personal route edits a machine credential, or the reverse | `api_scopes IS NULL` / `IS NOT NULL` in the statement | `SVC_GW_AUTH_0103`, `0107` |
| A credential edits credentials | both routes unreachable, registry test, bearer attempt | `SVC_GW_AUTH_0103` |
| An empty list silently grants everything | machine: 422; personal: an empty list is 400 and every marketplace is only `allMarketplaces: true`; portal never sends an empty list | `SVC_GW_AUTH_0102`, `0104`, `0107`, `0108` |
| An edit resurrects or alters a dead token | liveness is part of the statement | `SVC_GW_AUTH_0103` |
| Edit and rotate race, edit lost | revoke returns the row; successor built from it | `SVC_GW_AUTH_0105` |
| Concurrent edits give a ledger that cannot be replayed | `FOR UPDATE`, before read in the statement | `SVC_GW_AUTH_0106` |
| Change without entry | same transaction | `SVC_GW_AUTH_0106` |
| Extra fields change a dimension the edit must not reach | strict body | `SVC_GW_AUTH_0107` |
| The edit changes secret or expiry | one column set; whole-row comparison | `SVC_GW_AUTH_0105` |
| The narrowed token still fetches | no cache; real facade fetch after edit, and in a real browser | `SVC_GW_AUTH_0102`, `0108` |

Known limits: widening by an administrator is not four-eyes; the ledger entry is the control.

## Risks / Trade-offs

- In-place widening of a system token empowers an existing secret. Deliberate; stated in the
  portal and the ledger; flagged to the owner.
- A no-op edit writes an entry. Accepted for simplicity.

## Grill and rubberduck record (settled from the code, no one to ask)

- *An empty list on the personal route widens to every marketplace; is that an accident
  waiting to happen?* It was kept at first, because creation and the brief said so, and the
  owner then decided against it: an empty list is refused and every marketplace is stated as
  `allMarketplaces: true` (D2, and the last section).
- *Is a stolen browser session a way to widen?* Yes, as it already is for creating a token
  with any scope; an edit grants nothing creation does not (D4). The ledger names the actor.
- *Does the portal's CSRF protection cover `PATCH`?* The shared `api()` helper sends the
  token header on every method and the server's session-bound CSRF applies to every unsafe
  method, so `PATCH` needs no new wiring; the e2e path proves it through a real session.
- *Stale dialog.* A dialog opened on one list and saved after another change overwrites
  (last write wins). Accepted: the ledger holds the chain and the dialog shows the reach it
  started from. No If-Match style precondition: it adds a field and a refusal for a
  one-owner credential.
- *Delta bodies (add or remove one scope)* were rejected in D1; *a second administrator
  approving a widening* in D4, and flagged to the owner.

## Revision after the owner's decisions

The owner reviewed the decisions flagged in the pull request. Four stand as built: widening a
system access token needs only the administrator role; session-derived credentials are editable
like any personal token; a no-op edit is still audited; the last write wins between concurrent
edits. Two changed the work:

1. **Every marketplace is an explicit statement on a personal edit.** The owner wants a user to be
   able to make a token reach every marketplace on any edit, however it was created, as a
   statement and never as an empty list. The body takes **either** a non-empty `scopes` list
   **or** `allMarketplaces: true`. An empty list, neither, both, and `allMarketplaces: false` are
   refused 400 with a message naming the field to use (the check runs before the token is looked
   up, so it never reveals whether a token exists). Creation is unchanged. The ledger writes
   `every marketplace` in words on either side of an edit, never an empty array. `TokenService.updateScopes`
   now takes `null` for every marketplace and refuses an empty list as defence in depth. The
   portal's edit control sends `allMarketplaces: true` for *Every marketplace* and a non-empty list
   otherwise (`updateBodyFor`); nothing a user sees changed. Reqstool text for `GW_AUTH_0083`, `GW_AUTH_0088`
   and `SVC_GW_AUTH_0102`, `0107`, `0108` was reworded to match. Tests first: five of the eight
   `TokenScopeEditTests` failed on the first run (the new field was refused as unknown, the empty list
   was accepted), and two portal tests failed on the old body shape; a limited token widened to every
   marketplace, an unscoped token narrowed, and a rotation after the widening keeping it are covered.
2. **The four `revoke` operation ids are not pinned.** They renumber (D12). The contract check was run
   the way `.github/workflows/api-contract.yml` runs it: `oasdiff breaking <merge-base openapi.json>
   src/main/frontend/openapi.json --fail-on ERR` from the repository root, which reads `.oasdiff.yaml`.
   Result: no breaking change (`oasdiff changelog`: 0 error, 0 warning, 5 info: two endpoints added,
   three operation ids replaced). The workflow therefore ends at "The contract change is additive",
   and neither the label nor `!` in the title is needed. The gate reads no version: it diffs every
   pull request against its merge base whatever the latest tag is, so how `compatibility.md` dates the
   start of the promise does not change this result.
