# Design: rename-connector-to-vetter

## Decision 1 — The split, stated once

A **vetter** is the element of the vetting chain: whatever examines a
quarantined, SHA-pinned snapshot and answers with a verdict and findings. The
four built-ins are vetters. Each operator-configured external one is a vetter.

A **connector** is the transport that lets a vetter running outside the gateway
take part: the HTTP trigger/callback contract configured under
`skills-gateway.vetting.external[*]` — the POST of the evidence bundle, the
normalized `{state, reportUrl, findings[]}` answer, the timeouts and caps that
make an inconclusive answer fail closed.

Rule of thumb, and the sentence the glossary carries: **every external
connector contributes one vetter; a built-in vetter has no connector.**

That is why `ExternalVettingConnector`, `ExternalConnectorProperties`,
`ExternalVettingConnectorRegistrar` and the configuration prefix keep their
names while everything else moves. `ExternalVettingConnector implements Vetter`
is the split made executable: the class is the connector, the interface it
satisfies is the vetter, and nothing downstream of `vet()` can tell which kind
it is holding.

## Decision 2 — Break rather than alias

The alternative was to add the new names beside the old ones — a
`Vetter extends VettingConnector`, a second REST path, a ledger event written
under both spellings. Rejected: every one of those leaves the ambiguous word in
place and gives the two names time to drift, which is the defect this change
exists to remove, while doubling the surface a reader has to reconcile.

The project is pre-1.0 and the compatibility promise starts at 1.0, so the cost
of breaking is a regenerated client and a migration that runs itself. The PR
title carries `!` because the CI breaking-change gate requires the declaration,
and the two moved paths, the renamed DTO fields, the two ledger event names and
the two finding rule ids are what it declares.

## Decision 3 — `vetter-not-run`, not `vetter-disabled`

The finding rule id recorded in place of a vetter an administrator switched off
was `connector-disabled`. The ledger event written when that switch is flipped
was *also* `connector-disabled`. The mechanical rename would have carried the
collision across intact, so it does not: the rule id becomes **`vetter-not-run`**
and only the ledger event keeps `vetter-disabled`.

The new spelling is also the more honest one. The finding's job is to say the
vetter did not run — `DISABLED` is *why*, and the verdict state already carries
it. A reader of the ledger can now tell a state from an event by the string
alone.

## Decision 4 — A migration, against the standing single-migration rule

`.claude/skills/code-conventions` says schema changes fold into `V1__init.sql`
until the owner says otherwise. The owner has said otherwise for this change, so
`V2__rename_connector_to_vetter.sql` is a real migration:

- `vetting_verdicts.connector` → `vetter`, and its unique constraint with it;
- `snapshot_vetting_overrides.blocking_connectors` → `blocking_vetters`;
- `connector_toggles` → `vetter_toggles`, with its column, primary key, checks,
  unique constraint, foreign key, index and sequence.

Renames rather than add-and-backfill: the gateway is the only writer of these
tables, so there is no window in which two spellings must both work.

**Waivers are rewritten; recorded findings are not.** A waiver is a standing
acceptance matched against a rule id, so a waiver written for `connector-error`
has to follow the rename or it silently stops covering the finding it was
created for. A `vetting_findings` row is historical evidence of what a run said
on the day it ran, and rewriting it would falsify the record; the next
re-vetting run replaces it with the new spelling on its own.

`V1__init.sql` is left untouched, including the comment that still names
`VettingConnector.name()`. It is the migration already applied to every existing
database, and editing it would change a checksum Flyway has recorded.

## Decision 5 — What the ADRs keep

`docs/decisions/*.md` are immutable history and are not edited. The summaries in
`docs/manual/reference/decisions.md` keep the wording of the decision as taken —
ADR 0009 is titled *the external vetting connector contract* and that is what was
decided — and each affected summary gains a dated one-line note that the term was
renamed on 2026-09-15, pointing at the glossary. A reader who arrives from the
ADR is told the vocabulary moved; the record of the decision is not rewritten
under them.

`docs/language-decision.md`'s row 18 ("Connector ecosystem — out-of-process by
contract") is left alone for the same reason and is additionally still correct
under the narrow meaning: out-of-process by contract *is* the connector.

## Decision 6 — Scope of the mechanical pass

The rename is applied by substring substitution over `Connector`/`connector`,
with the six surviving identifiers protected, and then reviewed by hand. Two
classes of hand-correction were needed and are worth naming so a reviewer knows
where to look:

1. **`VettingConnector` → `Vetter`, not `VettingVetter`.** The interface loses
   its prefix; the mechanical pass would have kept it.
2. **"vetting connector" in prose.** Where it meant the chain element it became
   "vetter"; where it meant the transport — `ExternalConnectorProperties`'
   binding errors, the `ExternalVetRequest`/`ExternalVetResponse` javadoc, the
   *External connectors* sections of `vetting.md` and `configuration.md`,
   `GW_VETTING_0024` – `GW_VETTING_0027` — it became "external vetting
   connector".

The final check is the grep: `connector` survives only where it names the
transport.
