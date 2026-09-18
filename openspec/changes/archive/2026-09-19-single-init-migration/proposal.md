# Proposal: single-init-migration

## Why

The schema is described in two places as a single `V1__init.sql`, but five more
migrations have accumulated behind it, and parallel PRs land them out of order —
which is why the live runner has to set Flyway's out-of-order flag to start at
all. The convention and the tree disagree, and the tree is winning by accident
rather than by decision.

The owner has settled it: **there is no backwards compatibility between versions
until 1.0.0 is released.** That covers persisted state, not only the API
contract. There is no deployed database whose rows a migration has to repair, so
an incremental chain buys nothing and costs the ordering problem it creates.

One migration that is edited in place is the honest expression of "every version
starts from a fresh schema".

## What Changes

- **BREAKING (schema).** `V2__rename_connector_to_vetter.sql`,
  `V3__namespace_webhook_events.sql`, `V4__access_token_last_used.sql`,
  `V5__facade_credential_kind.sql` and `V6__vetting_chain_settings.sql` are
  folded into `V1__init.sql` and deleted. `V1`'s checksum changes, so **any
  existing database fails Flyway validation and must be recreated** — including
  the local live instance. This is exactly the breakage the pre-1.0 position
  permits.
- **BREAKING (requirement).** `GW_WEBHOOK_0008 — Namespaced lifecycle events`
  loses its final sentence, which promises that the system rewrites every
  existing subscriber's stored event filter when a name in the vocabulary
  changes, so that a subscriber keeps receiving what it asked for without
  operator action. The mechanism that delivered it *was* `V3`, and there is no
  longer a migration to carry it.
- The two pure data-repair statements disappear with their files: the
  `vetting_waivers.rule_id` rewrite from `connector-*` to `vetter-*`, and the
  `webhook_subscribers.events` prefixing. Both existed to repair rows that no
  deployment has.
- The convention is recorded where the next author will read it: **while pre-1.0,
  a schema change edits `V1__init.sql` rather than adding a versioned
  migration.**
- The live runner's Flyway out-of-order flag is removed, its reason having gone.

### This narrows a requirement; it does not weaken a test to pass

`SVC_GW_WEBHOOK_0008` currently reads `V3__namespace_webhook_events.sql` off the
classpath and executes it against subscribers written straight to the table. That
half of the test goes with the file it executes. Everything else that test
asserts **stays, unchanged**: that every subscribable event is namespaced under
the subject it is about, that a legacy spelling is refused by the API rather than
silently accepted, that a wildcard filter and `audit.export` are left alone, and
that what actually arrives carries the namespaced name in both header and body.

The requirement is being deliberately narrowed by the owner's decision, and the
test is being narrowed to match it. It is not being loosened so that unchanged
behavior can keep passing.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `lifecycle-webhooks`: `GW_WEBHOOK_0008 — Namespaced lifecycle events` drops the
  migration-time filter-repair obligation. Its remaining obligations — the
  namespaced vocabulary, the refusal of a name outside it, and the same name in
  header and body — are unchanged.

## Impact

- **Schema**: `src/main/resources/db/migration/` becomes one file. Five files
  deleted, `V1__init.sql` edited in place.
- **Requirements**: `docs/reqstool/requirements.yml` — `GW_WEBHOOK_0008`
  description narrowed, revision bumped.
- **Tests**: `src/test/java/dev/skillsgateway/server/WebhookTests.java` — the
  `rewriteStoredFilters` helper and the two constants naming the migration go;
  the rest of `SVC_GW_WEBHOOK_0008` stays.
- **Docs**: the two sentences that describe `V1__init.sql` as the whole schema
  become true again; the new convention is stated where a contributor meets it.
- **In-flight change**: `corpus-aware-vetting` plans `V2__snapshot_facts.sql` and
  asserts "`V2__snapshot_facts.sql` is the first migration after `V1__init.sql`".
  Both need to follow the new convention.
- **Local operations**: `~/agents/skillsgateway/live/run-latest.sh` drops its
  out-of-order flag; the live database is recreated once.
- **No runtime behavior changes.** A fresh database built from the squashed
  `V1__init.sql` is identical to one built from the six-file chain — that
  equivalence is the change's central claim and what its verification has to
  establish.
