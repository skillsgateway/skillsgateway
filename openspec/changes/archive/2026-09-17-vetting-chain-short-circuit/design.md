# Design: vetting-chain-short-circuit

## Context

The relevant code as it stands:

- `VettingService` sorts the injected `List<Vetter>` by `order()` then `name()`
  once, in its constructor, and `run(...)` iterates that fixed list. Each vetter
  is run guarded (throw or timeout becomes an `ERROR` verdict), a verdict is
  recorded at an increasing `position`, and the states are aggregated by
  `VettingChain.aggregate`.
- `chainIdentity()` is `vetter@version` joined in chain order, stamped on the run
  so a changed answer about unchanged content is attributable (`GW_VETTING_0012 —
  Continuous re-vetting of approved snapshots`).
- `VetterToggleService.resolve(vetter, marketplaceId)` is the resolution rule:
  the per-marketplace row, else the global row, else the default. It returns the
  deciding row alongside the answer so a surface can name a default as a default.
- `WaiverEvaluation.evaluate(run, waivers, sha, now)` is the effective outcome —
  the one that gates approval. It re-derives each non-clearing verdict from its
  residual findings and re-aggregates.
- `VerdictState.DISABLED` is the existing "neither clears nor blocks" state, and
  `VettingChain.aggregate` still demands at least one clearing verdict, so a run
  of nothing but `DISABLED` blocks.

## Goals / Non-Goals

**Goals**

- An administrator can make the chain stop after a blocking failure, globally or
  per marketplace, and can order the vetters, globally or per marketplace.
- A run that stopped early is honest evidence: it says which vetters never looked,
  and it can never be read — or waived — into a clean chain.
- Both settings are recorded with the run and are administrator-only and audited.

**Non-Goals**

- Stopping on `ERROR`, `PENDING` or `WARN`. Only a blocking `FAIL` stops the
  chain; see decision 2.
- A webhook event for either setting. The audit ledger is the record both are
  reserved to, and the event vocabulary (`GW_WEBHOOK_0009 — Marketplace
  administration event webhooks`) is a published payload contract worth extending
  when a receiver asks for it rather than ahead of one.
- Per-repository or per-plugin scoping of either setting.
- Automatically re-running the chain when a waiver is written. The gate simply
  stays shut until a run that got all the way through exists; see decision 6.
- Folding either setting into the declarative estate; see "Estate integration".

## Decisions

1. **One mode, two values, resolved by the rule that already exists.**
   `ChainMode` is `RUN_ALL` (stored `run-all`, the default) or `STOP_AFTER_FAIL`
   (stored `stop-after-fail`). `VettingChainSettingsService.resolveMode(marketplaceId)`
   is the same three-step rule as the toggle: the per-marketplace row, else the
   global row, else `RUN_ALL` — and it returns the deciding row and a `ChainSource`
   the same way, so the administrative surface can name a default as a default.
   *Alternative rejected:* a boolean `shortCircuit`. A named mode leaves room for
   a third policy (stop on any non-clearing verdict, say) without a second flag
   contradicting the first.

2. **Only a blocking `FAIL` stops the chain.** Not `ERROR`, not `PENDING`.
   `ERROR` means the vetter broke, which is a fact about the gateway rather than
   about the content, and letting a crash silence the rest of the chain would turn
   a flaky vetter into a coverage outage. `PENDING` has not answered yet, so there
   is nothing to conclude from. `WARN` does not block, so it cannot stop anything.
   This is `VerdictState.FAIL` exactly, checked on the verdict the chain just
   produced.

3. **The stop respects the waivers active at that moment.** The chain stops when the
   snapshot is *already condemned*, and a finding a reviewer has accepted does not
   condemn it, so `VettingService` asks `WaiverEvaluation.stillObjects` before
   setting the stop — the same `Verdict.of` re-derivation the effective outcome
   uses, so what stops the chain and what gates the approval cannot disagree.
   Without this the mode would make waivers useless: the same verdict would fail
   and stop the chain on every later run, so the fresh complete run decision 6
   points the reviewer at could never happen and the gate would stay shut for
   good. The waivers are read once per run, and only under `stop-after-fail`.

4. **`NOT_REACHED` is a new state, not a reuse of `DISABLED`.** Both are absences
   rather than conclusions and both neither clear nor block, but the cause differs
   — an administrator's standing decision versus a chain that ran out of road —
   and the ledger and the reviewer surface must be able to tell them apart.
   `VerdictState.NOT_REACHED` is added to the enum and `'not_reached'` to the
   `vetting_verdict_state` PostgreSQL type. It carries an informational finding
   naming the vetter that stopped the chain, so the run says *why* it stopped
   without a join.
   *Alternative rejected:* recording nothing for the vetters after the stop. A
   silently shorter chain is exactly what `GW_VETTING_0029.2 — a disabled vetter
   is recorded, not hidden` rejected for the same reason.

5. **A vetter switched off still records `DISABLED`, even after the stop.** The
   toggle is consulted first: a disabled vetter after the stopping failure reads
   `DISABLED`, not `NOT_REACHED`, because the administrator's decision is the
   older and more informative fact and it holds whatever the chain did. This keeps
   the two states answering two different questions rather than racing.

6. **A run carrying a `NOT_REACHED` verdict is blocked, whatever the waivers say.**
   This is the load-bearing rule of the change and it lives in
   `WaiverEvaluation.evaluate`, the one place the effective outcome is computed.
   Without it, waiving the finding that stopped the chain would leave
   `[PASS(residual), NOT_REACHED, NOT_REACHED]` — one clearing verdict, nothing
   blocking — and the gate would open on a run whose later vetters never looked at
   the content. So: if any verdict in the run is `NOT_REACHED`, the effective
   outcome is `BLOCKED` and those vetters are named in `blockingVetters`. The
   remedy is a fresh run, which is what an administrator or the re-vetting sweep
   already has an endpoint for; once the waiver suppresses the finding, the next
   run does not stop and the gate is decided on complete evidence.
   `VerdictState.NOT_REACHED.blocking()` stays `false` — per verdict it really is
   neither clearing nor blocking, exactly as the issue asks — and
   `effectiveState` returns it unchanged so waiving its bookkeeping finding cannot
   promote it to `PASS`, the same trap `DISABLED` already has a guard for.
   *Alternative rejected:* making `NOT_REACHED.blocking()` return `true`. It reads
   as "this vetter objected", which it did not, and it would make the recorded
   outcome say the wrong thing about which vetters are the reason.

7. **The order is an override list, and the resolved order is total.** An order
   setting is a list of vetter names. The resolved order is: every vetter the
   override names, in the order it names them; then every vetter it does not name,
   by its built-in `order()` and then its `name()` — the existing rule, unchanged.
   Ties are therefore impossible in either half, and a vetter added by a later
   release (or an operator's new external connector) appears at its natural
   position rather than disappearing or landing arbitrarily. The override is
   refused if it names a vetter that does not exist, or names one twice (422) —
   the same fail-loud stance the toggle takes, for the same reason: a typo that
   matched nothing is a control an administrator believes they moved.

8. **The chain identity carries both.** `chainIdentity(marketplaceId)` becomes
   `<vetter@version>,…;mode=<mode>` with the vetters in the *resolved* order, so a
   reordering changes the identity by construction and needs no separate field.
   The run's `chain` column is unchanged in shape and stays a single string, and it
   still opens with the chain itself; a row recorded before the mode existed simply
   has no `;mode=` suffix, which reads correctly as what it is.

9. **Storage mirrors `vetter_toggles`, one table per setting.** `vetting_chain_modes`
   and `vetting_chain_orders`, each with `marketplace_id` nullable (null = global),
   `UNIQUE NULLS NOT DISTINCT (marketplace_id)`, upserted in place, plus `reason`,
   `updated_by` and `updated_at`. Two tables rather than one row with two nullable
   value columns, because each setting has its own note, its own acting
   administrator and its own timestamp, and one row would have to carry two of
   each or lose the attribution the toggle establishes. The order is stored as
   `TEXT[]`, not a join table: it is a whole value that is replaced, never
   partially edited, and the repository's job is exactly to hand it back as a list.
   This is a new Flyway migration (`V6`), not a fold into `V1`: the repository has
   carried incremental migrations since `V2`.

10. **The API stays additive.** `GET /api/marketplaces/{name}/vetting-chain` keeps
   its array response and simply returns the resolved order — a reordering is
   visible there without a shape change. The chain-level settings get their own
   sibling read, `GET /api/marketplaces/{name}/vetting-chain-settings`, rather than
   wrapping the existing array in an object, which would be a breaking change to a
   contract that is additive within a major. Writes are
   `PUT /api/vetting/chain-mode` and `PUT /api/vetting/chain-order`, both taking an
   optional `marketplace` (omitted = global) and an optional `reason`, mirroring
   the toggle's request shape. `GET /api/vetting/chain-settings` lists every
   setting, as `GET /api/vetting/vetter-toggles` does.

11. **Administrator-only to set and to read, and audited on every change.** Both
    endpoints and both reads call `roleService.requireAdmin`, for the reason the
    toggle records: the controls that govern the chain, and even the visibility of
    their settings, must not be reachable by a marketplace-scoped approver, who is
    often the owner of the content the chain governs. Every change writes a ledger
    entry — `vetting-chain-mode-set`, `vetting-chain-order-set` — naming the
    principal, the scope, the new value and the note, through the one service both
    the API and any future reconciliation would use.

12. **Reordering in the portal is buttons, not drag.** Move-up and move-down
    buttons with accessible names (`Move secret-scan up`) on an ordered list below
    the flow, with the pending order applied locally and saved by an explicit
    "Save order" button. Keyboard-operable by construction rather than by a second
    implementation beside a pointer gesture, and one audited write per intended
    reordering rather than one per hop. Drag-and-drop is not added: it would have
    to carry an equivalent keyboard path anyway, and this *is* that path.

## Risks / Trade-offs

- **[A reviewer sees less of what is wrong]** → the mode is off by default, is an
  administrator's explicit act, is recorded on the run and in the chain identity,
  and the portal says in words that the chain stopped early and where. The cost is
  real and the surface states it rather than hiding it.
- **[A truncated run reads as a clean one]** → decision 6: a run carrying a
  `NOT_REACHED` verdict has a blocked effective outcome, full stop, and the
  adversarial test for it (waive the stopping finding, assert the gate stays shut)
  is the one that must fail before the rule is written.
- **[An order override silently drops a vetter]** → the resolved order appends
  everything the override does not name, so no vetter can be removed by omission,
  and an unknown name is refused rather than ignored.
- **[The mode becomes a way to clear content]** → it cannot produce a clearing
  verdict; `VettingChain.aggregate` still demands at least one, and every
  `NOT_REACHED` is non-clearing. A chain that stops at its first vetter records one
  `FAIL` and the rest `NOT_REACHED`, which blocks twice over.

## Estate integration (deliberate, API-only — following the toggle's precedent)

CLAUDE.md's estate obligation asks that API-managed runtime state extend
`skills-gateway.estate.*` in the same PR **or** say why it is deliberately
API-only. These two settings are **API-only, explicitly following the reasoning
the vetter toggle recorded** in
`openspec/changes/archive/2026-09-09-admin-vetting-override/design.md`
("Estate integration (deliberate, temporary API-only)"), which still holds and
still has no estate entry: folding a vetting-chain setting into the declarative
estate means widening the `Estate` record, and because Spring
`@ConfigurationProperties` records bind through a single canonical constructor,
every `new Estate(…)` call site changes with it — orthogonal churn against
unrelated tests, in a change whose reviewability is the thing protecting a trust
boundary.

Saying so explicitly rather than silently: the mode and the order belong in the
declarative estate on exactly the same terms as the toggle, and they should
arrive together with it in the follow-up that reconciles vetter settings, not
before. The reconciler pattern is the standard one — a `DeclaredChainMode` and a
`DeclaredChainOrder` reading the stored setting for idempotency and writing
through the same audited `VettingChainSettingsService` path the API uses. Doing
it here would mean adding a sixth *and* a seventh estate object type for settings
whose first estate consumer does not exist yet, which the stop rule refuses.

## Migration Plan

`V6__vetting_chain_settings.sql`: add `'not_reached'` to the
`vetting_verdict_state` enum and create `vetting_chain_modes` and
`vetting_chain_orders`. No data migration — an empty pair of tables is exactly
today's behaviour (every vetter runs, in its built-in order), and a run recorded
before this change has no `NOT_REACHED` verdict and no `mode=` in its chain
identity, both of which read correctly.

Note the PostgreSQL enum rule from the code conventions: a value added by a
migration cannot be *used* in the transaction that adds it. Nothing here needs to
— the new value is written by application code long after the migration commits.

## Open Questions (Decisions to confirm)

- **Stopping on `ERROR`**: decision 2 says no. An operator whose external vetter
  times out routinely might want the opposite; that would be a third mode rather
  than a change to this one.
- **Ledger event names**: `vetting-chain-mode-set`, `vetting-chain-order-set` —
  confirm the spelling before a SIEM consumer depends on it.
- **Re-running on a waiver**: the gate stays shut until a complete run exists, and
  the reviewer has to ask for one. Automatically triggering a run when a waiver is
  written against a short-circuited run would be a convenience; it is deliberately
  not in this change.
