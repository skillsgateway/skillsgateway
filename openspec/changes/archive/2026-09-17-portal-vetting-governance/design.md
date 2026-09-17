## Context

See `proposal.md` — Why. The constraints that shape the approach:

- Three settings (vetter enablement, chain mode, vetter order) each resolve
  **marketplace → global → default**, and the rule lives in exactly one place per
  setting: `VetterToggleService.resolve` and
  `VettingChainSettingsService.resolveMode`/`resolveOrder`. The portal deliberately
  does not hold a second copy; `useMarketplaceVettingChain`'s own comment records why.
- The absence of a setting is its own value (`ChainSource.DEFAULT`), not a missing
  one. Nothing in the API can currently *remove* a setting — both stores upsert in
  place on their scope key.
- The API contract is additive within a major (`docs/manual/reference/compatibility.md`
  — "The API contract").
- Everything here is administrator-only at the controller, for the reason
  `VetterToggleController` records: a marketplace-scoped approver is frequently the
  owner of the content the chain governs.
- The audit ledger's `marketplace` column is `NOT NULL`; the gateway-wide scope is the
  `-` placeholder the existing global-scope events already use.

## Goals / Non-Goals

**Goals:**

- The estate-wide half of the three settings gets an administrative surface with the
  same controls, vocabulary and source-naming as the per-marketplace half.
- Clearing an override is a real removal, observable as the source returning to
  `GLOBAL`/`DEFAULT`.
- An estate-wide change is one intent at the API, one audited act on the ledger, and
  never reports a partial failure as a success.
- No second copy of any resolution rule in the browser.

**Non-Goals:**

- No estate objects for these settings (see the Estate decision below).
- No change to how a chain run behaves — this change adds no vetter, no verdict state,
  and no new resolution level.
- No scheduling, no "apply later", no saved bulk recipes.
- No bulk action that spans more than one kind of change in one request.

## Decisions

### 1. Bulk shape: one endpoint, not a browser fan-out

`POST /api/vetting/chain-settings/bulk` takes the marketplaces and one action.

A fan-out from the browser was the cheaper option and is rejected: it makes the act
un-auditable. N loose ledger entries with nothing tying them together cannot be told
apart from an administrator who happened to change five marketplaces over five minutes,
which is precisely the distinction an auditor of an estate-wide change needs. It also
puts the atomicity story in the browser — a closed tab halfway through leaves the
estate in a state nobody intended and nothing recorded that it was intended.

One endpoint gives the gateway the whole intent, so it can validate the shape once,
mint one correlation id, and stamp it on every entry it writes.

**Alternatives considered.** (a) Fan-out — rejected above. (b) Extending the existing
`PUT`s to accept a list instead of a single `marketplace` — rejected: it would make the
per-marketplace endpoints answer with a different shape depending on the request, and
the single-marketplace response (`ChainModeSetting`) has no room for a per-item outcome.
(c) Three bulk endpoints, one per setting — rejected: the four actions share the
selection, the reason, the correlation id and the whole result shape; splitting them
triples the surface to save one discriminator field.

**Request.**

```json
{"marketplaces": ["a", "b"],
 "action": "set-mode" | "set-order" | "set-vetter" | "clear",
 "mode": "stop-after-fail",
 "vetters": ["secret-scan", "license-scan"],
 "vetter": "secret-scan", "enabled": false,
 "clear": ["mode", "order", "vetter"],
 "reason": "…"}
```

**Response** — `200` when every marketplace succeeded, `207 Multi-Status` when any did
not. `207` is the honest code and is what keeps a generic client from reading a partial
failure as a success; the portal reads `results` either way and names each failure.

```json
{"correlationId": "9f1c…",
 "applied": 1, "unchanged": 0, "failed": 1,
 "results": [{"marketplace": "a", "status": "applied", "detail": "mode=stop-after-fail"},
             {"marketplace": "b", "status": "failed", "detail": "marketplace 'b' not found"}]}
```

**Validate whole, apply per item.** Everything knowable without touching a marketplace —
an empty selection, an unknown action, an unknown or omitted mode, an empty order, an
unknown vetter, a vetter named twice, an empty clear list — is refused `422` before
anything is written or audited, keeping the existing "a refused change writes nothing"
stance. What is left is per-marketplace: a name that no longer resolves (another
administrator deleted it between the page load and the apply) fails that item and no
other. This is the only way partial failure can arise, and it is the case the portal has
to render honestly, so it is deliberately kept reachable rather than converted into a
whole-request refusal.

Application is delegated to the existing `VetterToggleService.set`,
`VettingChainSettingsService.setMode`/`setOrder` and the new clears, so the bulk path
cannot drift from the single path in validation, storage or audit shape. Each call
lands its own ledger entry; the bulk service appends ` bulk=<correlationId>` to the
detail the service already writes. No summary entry is added: the correlation id on
every entry is what an auditor needs, and a second event that could disagree with the
entries it summarises is a liability, not a feature.

### 2. Clearing an override: the same endpoint, not three `DELETE`s

The API cannot express a clear today. A `PUT` can only write a value, and writing the
default value as an override is a different fact: the source stays `MARKETPLACE`, so a
later change to the global default would not reach the marketplace, and the overrides
list would keep showing it as a departure. Faking it client-side is therefore not on the
table.

The clear is `action: "clear"` on the bulk endpoint, and the single-row *clear override*
button is that endpoint applied to one marketplace. That is the smallest additive
surface that can express it: one new endpoint rather than one new endpoint plus three
`DELETE`s, and the single case is the bulk case with a selection of one, so there is one
path to test and one ledger shape.

**Alternatives considered.** `DELETE /api/vetting/chain-mode?marketplace=…`,
`…/chain-order`, `…/vetters/{name}/toggle` — three endpoints, three sets of status codes
and three sets of tests, all of which the bulk endpoint would still have to duplicate for
the estate-wide case. Rejected on surface area alone.

Clearing is **idempotent**: a scope with no setting is reported `unchanged` and writes
nothing to the ledger. An administrator clearing a selection where only two of eight
actually override should not generate six entries recording that nothing happened.

New ledger events: `vetter-toggle-cleared`, `vetting-chain-mode-cleared`,
`vetting-chain-order-cleared`. New repository methods `deleteMode`, `deleteOrder`,
`delete(vetter, marketplaceId)`, each returning whether a row went away, which is what
drives `applied` versus `unchanged`.

### 3. Reading the default chain: two additive reads, not arithmetic in the browser

The page needs the chain as it applies to a marketplace with no override. The browser
could compute it — global mode else `run-all`, global order, global toggle else enabled —
but that is a second copy of the resolution rule, free to disagree with the one that
decides what actually runs. The codebase already refused that trade once.

`GET /api/vetting/global-chain` (→ `List<ChainVetterView>`) and
`GET /api/vetting/global-chain-settings` (→ `ChainSettingsView`) mirror the
per-marketplace pair exactly, so the portal reuses `VettingFlow`, `marketplaceFlow` and
the generated types with no new shape. `ChainSource` for these reads is `GLOBAL` when a
global setting exists and `DEFAULT` when none does — `MARKETPLACE` cannot occur, which
is what makes the page's claim ("as they apply to a marketplace with no override") true
by construction.

The existing `GET /api/vetting/chain-settings` and `GET /api/vetting/vetter-toggles`
stay as they are and feed the **overrides** section: they are the only reads that
enumerate per-marketplace settings without asking per marketplace, which is what a page
over the whole estate needs.

### 4. The overrides table

One row per marketplace that departs from the default, assembled from the two settings
lists plus `GET /api/marketplaces` (to turn `marketplaceId` into a name). This is not a
resolution rule — it is "does a row exist for this marketplace", which the list reads
answer directly — so it is browser-side without the objection in decision 3 applying.
Each row names which of the three it overrides and what to, and offers *clear override*
(all three) with per-kind clears available through the bulk confirm.

### 5. Estate: these settings stay API-only

Restating #391's reasoning rather than leaving it inferred, as CLAUDE.md's continuous
obligation requires. `skills-gateway.estate.*` declares objects an operator wants to
*exist* — marketplaces, webhook sinks, role grants — and reconciles them additively at
startup. The three chain settings are not objects; they are standing decisions whose
absence is meaningful (`DEFAULT`), and an additive reconciler has no way to express
"and nothing else", which is the only statement that would make a declared chain
trustworthy. A declaration that could add an override but never remove one would leave
the estate saying one thing and the gateway doing another — the exact failure the
vetter toggle was kept out of the estate to avoid.

This change does not weaken that: it adds a *removal* to the API, which is the
capability a declarative form would need, but a removal driven by an administrator's
explicit act with a reason on the ledger is not the same as a reconciler quietly
deleting overrides because a YAML file no longer mentions them. If the estate ever does
take these on, it needs its own change, with a stated stance on deletion. Adding no
estate object here is the deliberate answer to the obligation, not an omission.

### 6. Portal shape

- Route `/vetting`, page `src/main/frontend/src/pages/vetting.tsx`, sidebar entry in the
  **Governance** group, rendered only when `useIsAdmin()` is true. The nav entry is a
  hint; the server refuses all five reads independently, and the page renders a stated
  refusal for a session without the role rather than a blank or a spinner.
- `ModeControls`, `OrderControls` and the vetter toggle control are generalised to take
  an optional scope (`marketplace?: string`, absent meaning global) rather than being
  reimplemented. Their copy switches on the scope: "for this marketplace" becomes "for
  every marketplace that has no override of its own". This keeps `SegmentedGroup`, the
  keyboard reorder with its focus management and live region, and the audit hint in one
  implementation.
- Bulk edit is select → choose action → **confirm**. The confirm step is a list of the
  selected marketplaces with the before and the after for each, derived from the two
  settings lists, and it is where the reason is entered. Selection is checkboxes with
  real labels plus a select-all; every control is keyboard-operable and named, because
  the reorder control's rationale (`GW_VETTING_0034`) applies to the estate surface at
  least as strongly.
- Afterwards the result is a per-marketplace list — applied, unchanged, failed with the
  server's reason. A response with any failure renders as a failure, with an error
  toast, never a success toast with a footnote.

## Risks / Trade-offs

- **A bulk endpoint is a bigger blast radius than a single `PUT`.** → It is
  administrator-only, it validates the whole shape before writing anything, the confirm
  step in the portal states exactly which marketplaces change and how, and every write
  is on the ledger with a correlation id. It is also strictly narrower than the
  alternative an operator has today, which is an unaudited shell loop.
- **`207` is unusual and a naive client may treat it as success.** → It is documented in
  the API reference with the `results` contract; the portal reads `results` rather than
  the status code, so the honest rendering does not depend on the code being understood.
- **Partial application leaves the estate half-changed.** → Deliberate: the alternative
  is a transaction across N marketplaces that fails wholly because one name went away,
  which is worse for an operator mid-rollout. The response and the portal say exactly
  what did and did not change.
- **Five endpoints for one page is surface growth** (two reads, one bulk write). → The
  two reads exist to keep the resolution rule single-sourced, which is a rule this
  codebase has already paid for once; the bulk endpoint replaces three `DELETE`s and a
  fan-out. Nothing here is a new capability area: it is the estate-wide half of settings
  that already ship.
- **The overrides table needs the marketplace list to name ids.** → It is already the
  page's third read and is cached by TanStack Query; a marketplace that has vanished
  between reads renders its id with a "no longer registered" note rather than an empty
  cell.

## Migration Plan

Additive throughout: three new endpoints, three new repository deletes, no schema
change (the two settings tables and `vetter_toggles` gain deletes, not columns), no
change to any existing response shape. Rolling back is removing the page, the route and
the three endpoints; no data written by this change needs undoing, because a cleared
override is the absence of a row, which is the state the tables shipped in.
