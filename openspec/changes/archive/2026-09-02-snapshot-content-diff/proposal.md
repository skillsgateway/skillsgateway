# Proposal: snapshot-content-diff

## Why

A reviewer approving a held snapshot today sees two things that do not answer
the question they are actually asking. `GET /api/snapshots/{id}/content`
(GW_0020) enumerates *everything* the snapshot ships, so on the tenth ingestion
of a large marketplace the one new skill is a needle in the same list as the
forty that were approved months ago. `GET /api/snapshots/{id}/diff` (GW_0081)
is a *file* diff against the marketplace's currently served commit — precise,
but it speaks in paths and hunks, so "a skill moved from one plugin to another"
arrives as a delete and an add of two unrelated-looking `SKILL.md` files.

Issue [#236](https://github.com/skillsgateway/skillsgateway/issues/236) asks for
the missing middle: the same inventory the reviewer already reads, annotated
with what changed since the marketplace's **last approved** snapshot. That
turns "approve SHA `a1b2c3`" into "approve two new skills and one changed one".

The baseline is deliberately the last approved snapshot rather than the served
tip GW_0081 uses: the question a reviewer is answering is "what am I adding to
what my organisation already accepted", and an approval that has not published
yet, or a marketplace whose serving was interrupted, must not silently change
the answer.

## What Changes

- **Plugin/skill-level content diff (GW_0153).** New
  `SnapshotContentService.diff(snapshotId)` compares a snapshot's inventory
  against the inventory of the newest approved, live snapshot of the same
  marketplace, and classifies every plugin and every skill as `added`,
  `removed`, `changed`, `moved` or `unchanged`. `changed` is decided by
  comparing the JGit tree object of each skill's directory in the two commits,
  so a modification anywhere under a skill — not only in its `SKILL.md` — is
  detected. A skill that kept its content but changed plugin is reported once,
  as `moved`, naming the plugin it came from, instead of as an unrelated
  removal and addition.
- **New endpoint** `GET /api/snapshots/{id}/content-diff`. The obvious path,
  `/diff`, is already the file-level preview diff (GW_0081) and is not
  redefined: the API contract is additive within a major.
- **Portal** (`marketplace-detail.tsx`): the existing **Show contents** panel
  gains a "Changes since the last approved snapshot" section listing only what
  changed, with its own states for a first snapshot (no baseline) and for a
  snapshot that changed nothing.
- Requirement GW_0153 with SVC_GW_0153.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `marketplace-ingestion`: the snapshot content inventory (GW_0020) gains a
  companion diff of that inventory against the marketplace's newest approved
  snapshot, at plugin and skill granularity, including relocation of a skill
  between plugins and the no-baseline case.

## Impact

- **DB**: none. The baseline is selected with the existing
  `SnapshotRepository` reads; both commits already live in the marketplace's
  quarantine repository.
- **Backend**: `SnapshotContentService` (+`diff`, +`ContentDiff` DTOs),
  `SnapshotRepository` (+`latestApprovedByMarketplace`), `AdminController`
  (+`GET /snapshots/{id}/content-diff`).
- **API**: one additive endpoint; `openapi.json` regenerated.
- **Portal**: `marketplace-detail.tsx`, new
  `components/snapshot-content-diff.tsx`, `api/queries.ts`, MSW handlers.
- **Trust boundary**: none. The read is derived from the same quarantine
  content `GET /snapshots/{id}/content` already exposes to any authenticated
  session, and it decides nothing — no approval path reads it.
- **Docs** (same PR): `reference/api/marketplaces.md`, `reference/portal.md`,
  `guides/approving-snapshots.md`.
