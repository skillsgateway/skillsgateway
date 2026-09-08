## Context

See [ADR 0018](../../../docs/decisions/0018-domain-prefixed-requirement-ids.md)
for the decision itself (why domain prefixes, why wholesale, why now, the
12-domain table). This design covers the mechanics of applying it.

## Goals / Non-Goals

**Goals:**

- Rename every real requirement/SVC id to `GW_<DOMAIN>_NNNN` /
  `SVC_GW_<DOMAIN>_NNNN` everywhere it appears, with zero behavior change.
- Leave no file with a mix of old- and new-style ids for the same real
  requirement.
- Update the id-minting config (`.reqstool-ai.yaml`) so future ids are minted
  correctly under the new convention.

**Non-Goals:**

- No requirement text, scenario, significance, or SVC verification steps
  change. This is a rename, not a review of the requirements.
- No decomposition of further multi-behavior requirements (issue #299's
  remaining candidates: `GW_INGEST_0027` (was `GW_0164`),
  `GW_INGEST_0025` (was `GW_0156`)). Separate change.
- No renumbering of ids that were never real: reserved-then-abandoned gaps,
  and provisional ids in unimplemented change proposals. See Risks below.

## Decisions

**Mechanism: whole-file literal substring replacement, not regex
word-boundaries.** Every real top-level id is exactly `GW_` + 4 digits — fixed
width, so no id string is ever a strict prefix of a different id's string.
`SVC_GW_0158` contains `GW_0158` as a substring; replacing that substring
in-place correctly produces `SVC_GW_INGEST_0026` as a side effect, with no
separate `SVC_` case needed. The same holds for dot-notation children:
replacing the parent's bare substring inside `GW_0158.1` correctly yields
`GW_INGEST_0026.1`, so only the 172 top-level mappings were needed as input —
the 8 children were never a separate substitution. This was applied to every
`git ls-files`-tracked, UTF-8-decodable file in one pass.

**Domain assignment cross-checked against `openspec/specs/*/spec.md`, not
titles alone.** That capability grouping already existed and is authoritative
for what a requirement is about; it caught placements a title-only read got
wrong (e.g. `GW_0154` — fetch ledger records the advertised ref a want
resolves to — reads AUDIT from its title but lives in the `git-facade`
capability, and is `GW_FACADE_0006` here).

**Deltas here use `## RENAMED Requirements` (FROM:/TO:), not `## MODIFIED
Requirements`.** This project's reqstool-openspec convention keeps specs
id-only (`### Requirement: <ID>` / `#### Scenario: <SVC_ID>`, no duplicated
requirement text — reqstool is the SSOT for that). A pure id rename is
exactly what RENAMED is for, and avoids reproducing 172 requirement bodies
that aren't changing.

**`openspec/specs/*/spec.md` were edited directly by the same substitution
pass, ahead of this change's archival**, rather than left on the old ids
until archive. Both `openspec validate --all --strict` before and after this
change's archival pass with those files already correct, so there is no
functional difference — but it means this change's delta specs describe what
already happened rather than what archiving will apply. Flagged here rather
than silently: if this project's archive tooling expects to be the one that
applies the RENAMED operation to the live spec, applying it a second time
against already-renamed content is a no-op (the FROM header no longer exists
to match), which is the intended, safe outcome.

## Risks / Trade-offs

- **Coordination risk of a wholesale rename**: any other branch with an open
  PR touching `docs/reqstool/` at merge time would conflict badly. Checked at
  proposal time (`gh pr list --state open` -> none); re-check immediately
  before merging this PR, not just before opening it.
- **Domain placement is a judgment call for ~10 of 172 ids** (documented in
  ADR 0018) where a requirement's title suggests one domain and its OpenSpec
  capability suggests another, or where no domain in the 12-item list is a
  clean fit (e.g. `GW_FACADE_0016`, was `GW_0125` — "enumerated persisted
  values are database types" — a persistence-schema concern with no
  dedicated domain). Accepted as a one-time judgment call rather than adding
  a 13th single-requirement domain; ADR 0018 names the mechanism for
  revisiting one placement later (deprecate and reissue) without a second
  wholesale renumber.
- **Archived history now reads with mixed number styles in a few spots by
  design, not by omission**: passages narrating the old flat scheme's own
  gaps and reserved ranges (e.g. "`GW_FACADE_0023` is the highest committed;
  `GW_0173`-`GW_0180` are left to changes in flight" in the archived
  `add-webhook-payload-contract` evidence) mix a real, renamed id with bare
  numbers that were never real. That mixing is correct: the bare numbers
  describe placeholders in a numbering scheme that no longer exists, and
  renaming them would fabricate ids that were never minted.
