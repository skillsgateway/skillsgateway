# Design: virtual-catalog

## Context

The facade (`GitFacadeConfiguration.resolvePublished`) resolves any well-formed
name through `GitStorage.publishedIfServing(name)` and audits info-refs and
upload-pack by that name — so a published repository called `catalog` is
served, PAT-gated, and ledger-recorded with zero facade changes. Publication
(`ApprovalService.approve`) is a local JGit fetch from quarantine plus a forced
`refs/heads/main` update; unpublication (`GitStorage.unpublish`) is its exact
inverse. The catalog composes on top of exactly these primitives.

## Goals / Non-Goals

**Goals:**

- One URL (`/git/catalog`) serving the whole governed, approved estate.
- Contains only what marketplaces are *serving right now*; retracted content
  disappears without operator action.
- Approval gate, GW_0003 (local-only sources), and the ledger untouched in
  their guarantees.

**Non-Goals:**

- Per-team catalogs, entitlements, per-plugin/skill filtering (later #11
  slices).
- Serving history: the catalog's `main` moves; old catalog commits are not
  advertised by any ref.

## Decisions

1. **"Approved set" = what each published repo serves.** The rebuild
   enumerates registered marketplaces and takes `publishedIfServing(name)`'s
   `refs/heads/main` tip — not a DB query over snapshot states. The published
   repo is the single source of "served" truth the facade itself uses, so the
   catalog can never disagree with the facade: whatever revoked or unpublished
   a marketplace also removed exactly the ref the rebuild reads.

2. **Composition by local fetch + tree synthesis.** The rebuild fetches each
   served tip into the catalog repository (`refs/catalog/<marketplace>` — a
   local file-based fetch, object reuse, no content copying), then builds a
   root tree with one subtree per marketplace (the fetched commit's tree,
   nested under the marketplace name) plus a merged manifest blob at
   `.claude-plugin/marketplace.json`, commits it, and force-updates
   `refs/heads/main`. Internal refs `refs/catalog/*` are pruned for
   marketplaces that stopped serving; upload-pack advertises them, but they
   only ever point at content that is (or was) approved — never quarantine.
   Actually simpler and tighter: delete each `refs/catalog/<name>` after the
   tree is composed, so nothing but `main` (and its history) is advertised.

3. **Manifest merge.** Each constituent manifest was validated at ingestion
   (relative sources only). Merged entry: plugin name `<marketplace>-<name>`,
   source `./<marketplace>/<original-relative-path>`, other fields carried
   through. Catalog manifest metadata: name = the catalog name, owner = the
   gateway. Prefix collisions are theoretically possible
   (`a` + `b-c` vs `a-b` + `c`): the rebuild keeps the first in deterministic
   marketplace-name order and logs the loser — documented known limit, revisit
   if it ever bites.

4. **Rebuild triggers = the two publication-changing paths.**
   `ApprovalService.approve` (after the publish) and the re-vetting
   auto-quarantine path (after `unpublish`). Both call
   `CatalogService.rebuild()`, which is idempotent, synchronized (one rebuild
   at a time; serialized by a lock like ingestion's), and safe to call
   redundantly. A rebuild failure never fails the approval or the revocation —
   it is logged, and `POST /api/catalog/rebuild` repairs on demand. Retention
   never removes served refs (the last-served veto), so it needs no hook.

5. **Empty estate → empty catalog.** With nothing serving, the catalog serves
   a manifest with zero plugins rather than 404ing: "the estate is empty" and
   "the gateway is down" must look different to a consumer.

6. **Reserved name.** `skills-gateway.catalog.name` (default `catalog`) is
   refused by marketplace registration with 422 — the facade path would
   collide. Checked case-exactly against the same lowercase name rule
   registration already enforces. `enabled` (default true) gates the rebuild
   triggers and the endpoints; it does not delete an existing catalog repo.

7. **Provenance.** The catalog commit message carries the constituent list
   (`<marketplace> <sha>` per line); `GET /api/catalog` parses the served
   commit and returns `{sha, generatedAt, constituents:[{marketplace, sha}]}`.
   The ledger needs nothing new: fetches of `/git/catalog` are already
   recorded under the marketplace name `catalog`.

## Risks / Trade-offs

- [Catalog staleness if a rebuild trigger is missed] → rebuild is idempotent
  and manual; the two mutation paths are the only ones that move published
  refs. A missed rebuild self-heals on the next approve/revoke or manual call.
- [Large estates make rebuild heavy] → object reuse keeps it to tree/commit
  writes; content blobs are never copied. Acceptable for slice 1; measured
  before per-team catalogs multiply the work.
- [Plugin-name prefix collision] → deterministic winner + log; documented.
- [A revoked SHA remains reachable inside old catalog commits' history] →
  `main` is force-updated, old catalog commits become unreferenced except via
  history of main… so history must not retain them: the rebuild writes each
  catalog commit **with no parent** (history depth 1). A revoked constituent
  is then unreachable from any advertised ref immediately.

## Migration Plan

No schema change. New config block defaults on; first rebuild happens on the
first approve/revoke after deploy (or one manual `POST /api/catalog/rebuild`).
Rollback = revert; the catalog repo directory is inert without the code.

## Open Questions

None for this slice; entitlements and filtering deliberately deferred.
