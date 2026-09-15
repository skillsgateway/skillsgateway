# Proposal: rename-connector-to-vetter

## Why

One word carries two jobs in this project, and the ambiguity has already
produced a defect. **Connector** names the element of the vetting chain — the
thing that examines a snapshot and answers with a verdict — *and* the HTTP
trigger/callback contract that lets an external service do that job. So
`skills-gateway.vetting.external[*]` configures "connectors", the four built-in
scanners are also "connectors", and a sentence like "the switch covers every
connector" needs a second sentence to say which kind it means. The immediately
preceding change,
[#381](https://github.com/skillsgateway/skillsgateway/pull/381), existed
entirely to settle one such sentence.

The collision also reached the audit surface: the ledger event written when an
administrator switches the chain element off is `connector-disabled`, and so is
the finding rule id recorded on the run in its place. One string, two meanings,
on the surface whose whole purpose is that a reader does not have to derive
anything.

The owner's decision of 2026-09-15 splits the word:

- a **vetter** is the element of the chain: whatever examines a snapshot and
  answers with a verdict and findings — the four built-ins (`secret-scan`,
  `prompt-injection`, `license-scan`, `skill-conformance`) and each
  operator-configured external one;
- a **connector** is the transport that lets a vetter running outside the
  gateway take part: the HTTP contract under
  `skills-gateway.vetting.external[*]`, and nothing else.

Rule of thumb: every external connector contributes one vetter; a built-in
vetter has no connector.

## What Changes

**A rename. No behaviour changes**, beyond the identifiers the rename moves.

- **Java.** `VettingConnector` → `Vetter`; the four built-ins to `*Vetter`;
  `ConnectorToggle{,Repository,Service,Controller}` → `VetterToggle*`;
  `ChainConnectorView` → `ChainVetterView`; `VettingController`'s
  `ConnectorView` → `VetterView`; every field, parameter, javadoc, log and
  error message with it. `ExternalVettingConnector`,
  `ExternalConnectorProperties`, `ExternalVettingConnectorRegistrar` and the
  `skills-gateway.vetting.external` prefix keep their names — they **are** the
  connector — and `ExternalVettingConnector` now implements `Vetter`.
- **REST.** `PUT /api/vetting/connectors/{name}/toggle` →
  `PUT /api/vetting/vetters/{name}/toggle`;
  `GET /api/vetting/connector-toggles` → `GET /api/vetting/vetter-toggles`.
  `GET /api/marketplaces/{name}/vetting-chain` keeps its path; its item type
  and fields are renamed. `openapi.json` and `types.gen.ts` are regenerated.
- **Ledger.** `connector-disabled`/`connector-enabled` →
  `vetter-disabled`/`vetter-enabled`; the `vetting-verdict` entry leads with
  `vetter=state`; `revet-violation` carries `vetters=`. Finding rule ids:
  `connector-error` → `vetter-error`, and the rule id spelled
  `connector-disabled` → **`vetter-not-run`** — deliberately not
  `vetter-disabled`, so a rule id and a ledger event no longer share a string.
- **Database.** `V2__rename_connector_to_vetter.sql` renames
  `vetting_verdicts.connector`, `snapshot_vetting_overrides.blocking_connectors`
  and the `connector_toggles` table with its column, index, sequence and
  constraints, and rewrites stored waiver rule ids onto the two new spellings.
- **Requirements.** Titles, descriptions and rationales in
  `docs/reqstool/requirements.yml` and `software_verification_cases.yml` that
  mean the chain element say vetter; those that mean the external transport
  keep connector. Ids are unchanged; revisions are bumped.
- **Portal and docs.** Components, stories, tests, MSW handlers and UI copy;
  all of `docs/manual`, the `lifecycle` diagram pair, `PRODUCT.md`,
  `DESIGN.md` and `.claude/skills/architecture`. The glossary gains a
  **Vetter** entry and narrows **Connector** to the transport.

## Capabilities

### Modified Capabilities

Seven capability specs carry a requirement whose reqstool wording moved. **No
behaviour delta in any of them** — the ids are unchanged and the pointers they
hold are restated so the rename is recorded against the capability it touched.

- `snapshot-vetting` (21), including `GW_VETTING_0001` — *Ordered vetter chain
  at ingestion* —, `GW_VETTING_0029` — *The vetter on/off switch never becomes a
  blanket approval* — and `GW_VETTING_0031` — *Portal vetting chain flow*.
- `snapshot-approval` (2): `GW_APPROVAL_0002` — *Manual approval decision* — and
  `GW_VETTING_0028` — *Administrative override of a blocked vetting outcome*.
- `continuous-revetting` (3), `license-compliance` (3),
  `marketplace-ingestion` (3), `lifecycle-webhooks` (1).

## Compatibility

**Breaking**, deliberately, and declared in the PR title. Two API paths move,
two DTO type names and their JSON fields change, two ledger event names and two
finding rule ids change, and the schema is renamed under them. The project is
pre-1.0 and the compatibility promise starts at 1.0, so the cost is a
regenerated client and a migration that runs itself; carrying both spellings
instead would preserve exactly the ambiguity this change exists to remove.

## Out of scope

- **Renaming `ExternalVettingConnector` and friends.** They are the surviving
  meaning of the word.
- **`docs/decisions/*.md`.** ADRs are immutable history. The summaries in
  `docs/manual/reference/decisions.md` keep the wording of the decision as
  taken and gain a dated note that the term was renamed.
- **Rewriting recorded evidence.** Existing `vetting_findings` rows keep the
  rule ids they were written with; a re-vetting run replaces them. Only
  waivers, which are standing acceptances that must keep matching, are
  rewritten by the migration.

## The stop rule

This adds no capability, no estate object, no role, no sweep and no
configuration leaf — it removes a word. `ConfigSurfaceBudgetTests` and
`ContextBudgetTests` are untouched, and `docs/manual/capability-map.md` gains no
row.
