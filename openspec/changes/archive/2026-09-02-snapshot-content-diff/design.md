# Design: snapshot-content-diff

## Context

Three things already exist and constrain the shape of this change:

- `SnapshotContentService.content(id)` (GW_0020) reads
  `.claude-plugin/marketplace.json` at the snapshot's pinned commit inside the
  marketplace's **quarantine** repository and lists, per declared plugin, the
  skill directories under `<source>/skills/` that contain a `SKILL.md`.
- `SnapshotPreviewService.diff(id)` (GW_0081) already owns
  `GET /api/snapshots/{id}/diff`: a JGit `DiffFormatter` file diff against the
  *served tip* of the published repository, privileged to admin/approver.
- Every snapshot of a marketplace — held, approved or revoked — is a commit in
  the same quarantine repository, so a diff between two of them needs one
  repository handle and no fetch.

## Goals / Non-Goals

**Goals**

- A reviewer sees what a held snapshot changes relative to the last thing the
  organisation approved, in the vocabulary they review in (plugins, skills).
- `changed` is real: content modified anywhere under a skill directory.
- The three awkward cases are answered explicitly: no baseline at all, a plugin
  that disappeared, and a skill that moved between plugins.

**Non-Goals**

- Not a replacement for GW_0081's file diff; that stays the way to read the
  actual hunks, and this endpoint returns no file text at all.
- No change to what the approval gate reads. The diff is evidence for a person,
  never an input to a decision the gateway makes.
- No rename detection *within* a plugin (a skill directory renamed in place is
  an add plus a remove). Only the relocation the issue names — same skill name,
  different plugin — is tracked, because that one is a manifest reorganisation
  a reviewer would otherwise misread as a deletion.

## Decisions

1. **A new path, `/content-diff`, not a redefinition of `/diff`.** `/diff` is
   GW_0081's file diff. The contract is additive within a major, and two
   different answers behind one path would break every existing client at once.
   The name says what it diffs: the content *inventory*.

2. **The baseline is the newest approved, non-deleted snapshot of the same
   marketplace, excluding the addressed snapshot itself.** Added as
   `SnapshotRepository.latestApprovedByMarketplace` — a `state = 'approved' AND
   deleted_at IS NULL ORDER BY id DESC LIMIT 1`, the same predicate
   `approvedByMarketplace` already uses, so "approved" means one thing in the
   repository.
   *Alternative rejected:* the served tip (what GW_0081 uses). It answers a
   different question — "what will the facade start serving" — and it goes
   blank whenever publication is interrupted, which would turn a two-skill
   review into a forty-skill one at exactly the wrong moment. Excluding the
   snapshot itself matters for a revoked-then-re-reviewed snapshot: a snapshot
   is never its own baseline.

3. **`changed` is decided by the skill directory's tree object id, not by
   `SKILL.md`.** For each skill, `TreeWalk.forPath(repo, "<source>/skills/<name>",
   commit.getTree())` yields the subtree's `ObjectId`; two snapshots' skills are
   identical exactly when those ids are equal, because a git tree id is a
   recursive hash of everything under it. So an edited helper script or an added
   reference file makes the skill `changed`, which is the honest answer — a
   reviewer told "unchanged" about a skill whose scripts were rewritten has been
   actively misled.
   *Alternative rejected:* comparing the `SKILL.md` blob. Cheaper, and wrong in
   the one direction that matters on a security surface.

4. **A relocated skill is reported once, on its new plugin, as `moved`.** A
   skill name present in the baseline under plugin A and in the snapshot under
   plugin B — and under neither the other way round — is reported as `moved`
   with `movedFromPlugin: "A"`, and is *not* also listed as `removed` under A.
   If its tree also differs, the status is `changed` and `movedFromPlugin` is
   still set, so a relocation that also edited the content can never be read as
   a pure move.
   Matching is by skill name across plugins, which is the granularity the
   manifest itself works at.

5. **A removed plugin is a first-class entry.** A plugin the baseline declared
   and the snapshot does not is returned with `status: "removed"`, its baseline
   `source` and `description`, and every skill it had as `removed`. A diff that
   simply omitted it would hide a deletion, which is the one class of change a
   reviewer most needs to see.

6. **No baseline is stated, not simulated.** With no approved snapshot,
   `baselineSnapshotId` and `baselineSha` are `null` and every plugin and skill
   is `added`. That is the same honesty GW_0081 already applies to an unserved
   marketplace, and it keeps a first approval from looking like a no-op review.

7. **Access parity with `/content`, not with the preview pane.** The endpoint
   lives on `AdminController` beside `/snapshots/{id}/content` and requires only
   an authenticated session. Every plugin and skill *name* it returns is already
   returned by `/content` to the same caller; the baseline half describes
   content the marketplace is already serving. It exposes no file text — that is
   what makes it different from the preview reads, which stay privileged because
   they hand back the quarantined bytes themselves.

8. **Unchanged entries are in the payload, filtered by the portal.** The API
   answers "here is the inventory, classified", which is complete and lets a
   future consumer render either view; the portal shows only what changed,
   because the unchanged half is exactly what the existing inventory panel
   above it is already showing.

## Risks / Trade-offs

- **Cost.** One extra tree walk per snapshot plus one `TreeWalk.forPath` per
  skill per side. Bounded by the manifest's plugin count and by the same
  quarantine object store `/content` already reads; no new caps are introduced,
  and no file bytes are read.
- **Skill-name matching for moves** misreports the case where two different
  skills in two plugins happen to share a name and one of them is deleted while
  the other is added. The result is a `moved` where the truth is a coincidence;
  the content comparison bounds the damage — an identical tree means the two
  really are the same skill.
- **A purged baseline commit.** Approved snapshots are excluded from retention
  deletion (GW_0033), so the baseline's objects cannot be compacted away while
  it is the baseline. If a read still fails, it surfaces as the existing
  `IngestionException` → 502 rather than as a silently empty diff.

## Migration Plan

None: additive endpoint, no schema change, no behaviour change to any existing
surface.

## Open Questions

None.
