# Design: setup-wizard-preview-pane

## Context

The quarantine repository already holds every ingested commit under
`refs/snapshots/<sha>` (`IngestionService`), and `SnapshotContentService`
established the read pattern this change generalizes: open
`storage.quarantine(marketplace)`, parse the pinned commit, walk its tree with
JGit `TreeWalk` — never a filesystem path. The served baseline of a marketplace
is `refs/heads/main` of its published repository (`GitStorage.publishedIfServing`),
the same single source of truth the facade, the catalog and the adoption
reports use. Because approval copies the approved commit *from* quarantine
*to* published, the baseline commit object always also exists in the
quarantine object store — so a diff between "what this snapshot pins" and
"what is being served" is a two-commit diff inside one repository.

On the portal side, `pages/tokens.tsx` is the canonical form and the canonical
show-once dialog (`IssuedTokenDialog`); `pages/marketplace-detail.tsx` already
hosts the per-snapshot review surfaces (vetting report, revet panel, content
inventory) that the preview pane joins.

## Goals / Non-Goals

**Goals:**

- A consumer leaves the marketplace detail page with working, copy-pasteable
  client commands, without any new server configuration.
- A reviewer inspects the exact pinned bytes and the exact delta against what
  consumers currently receive, entirely inside the portal.
- Inspection is inert by construction: tree-addressed reads, size caps, binary
  detection, no HTML injection, nothing executed, nothing new served.

**Non-Goals:**

- No download/export of quarantine content (inspection renders text; it does
  not hand out archives).
- No diff between two arbitrary snapshots — the baseline is always the served
  tip, because that is the delta an approval decision is about.
- No server-side Markdown rendering, no syntax highlighting engine, no new
  frontend dependency for rendering.
- No change to what the facade serves or how approval publishes.

## Decisions

Each decision below was argued against (grill-me) and resolved from the
codebase; the losing branch is recorded where it was a near call.

1. **The wizard lives on the marketplace detail page, not the tokens page.**
   The composed artifact is per-marketplace — the add command and the clone
   URL embed `/git/{name}` — so the wizard needs a marketplace in hand; the
   tokens page has none. The detail page is also where a consumer lands after
   finding a marketplace (`marketplaces` list → detail), matching the portal's
   IA where every snapshot-scoped affordance already lives on the detail page.
   *Rejected branch:* a wizard on the tokens page with a marketplace picker —
   it duplicates the marketplace list into a select and inverts the user's
   actual flow ("I found this marketplace, now connect me to it").

2. **Base URL from `window.location.origin`; no new config property.** The
   browser reached the portal through the same origin git clients will use —
   the gateway serves portal and facade from one server (SPA + `/git/**`).
   A deployment fronting the gateway with a different external URL is exactly
   the deployment whose browser origin *is* that external URL. No
   `skills-gateway.external-url` property exists today and none is introduced.
   *Rejected branch:* a server-exposed external URL — it would be a new
   configuration surface whose only consumer guesses what the browser already
   knows.

3. **The wizard mints tokens through the existing flow and holds show-once.**
   It reuses `useCreateToken` and the same disabled-until-valid form rules as
   `tokens.tsx` (trimmed non-empty name, in-flight label). The minted
   cleartext is kept in component state only while the wizard dialog is
   mounted, substituted into the credential/CI snippets, and dropped on close;
   reopening shows `<YOUR_TOKEN>` placeholders. Existing tokens are listed
   nowhere in the wizard and no secret is ever re-displayed (the server only
   returns cleartext at creation, so the client cannot leak what it never
   has — the wizard simply must not cache it, and does not).
   *Rejected branch:* embedding the whole tokens page in the wizard — the
   wizard needs "give me a credential for these snippets", not lifecycle
   management, and the list would invite re-display expectations.

4. **Three endpoints, tree-addressed, in a new `preview` package.**
   - `GET /api/snapshots/{id}/files` — every path in the pinned commit's tree
     (path, size, binary flag), capped at 2 000 entries with an explicit
     `truncated` marker on the listing.
   - `GET /api/snapshots/{id}/file?path=` — one blob: metadata always
     (path, size, binary, truncated) and `text` only for non-binary content,
     cut at 128 KiB with `truncated: true`. A path with no tree entry — which
     includes every traversal shape, `../`, absolute, or otherwise, since
     `TreeWalk.forPath` matches tree entries and nothing else — is 404.
   - `GET /api/snapshots/{id}/diff` — `baselineSha` (nullable) plus entries
     `{path, type: added|modified|removed, binary, truncated, diff}` from
     JGit `DiffFormatter` over the two commit trees, per-entry diff text
     subject to the same 128 KiB cap, entry list capped at 500 with a marker.
   Caps are constants, not configuration: they defend the reviewer's browser,
   not a policy anyone tunes; making them properties would be configuration
   surface without a configurer. Rename detection stays off — an approval
   review wants "this path changed", not similarity heuristics.
   *Rejected branch:* one fat endpoint returning tree+manifest+diff — the
   file read is per-click and must not re-ship the tree; three small reads
   match how the page actually loads.

5. **Baseline = the served tip, resolved from published, diffed in quarantine.**
   `publishedIfServing(marketplace)` + `refs/heads/main` names the baseline
   commit; the diff itself runs inside the quarantine repository, where both
   commits' objects live (ingestion pinned one, and the other was ingested
   before it was ever approved). No published repository content flows anywhere
   new — only a 40-char SHA crosses between the two opens. When nothing is
   served (never approved, or unpublished/revoked) the response says
   `baselineSha: null` and lists every path as `added`, which is the honest
   answer ("approving this serves all of it").
   *Rejected branch:* "latest approved snapshot" from the database — it can
   disagree with the wire exactly in the retraction cases a reviewer most
   needs the truth (same argument as the adoption change's tip resolution).

6. **The reads are privileged: admin or approver of the snapshot's
   marketplace.** They return raw held quarantine bytes — the very secrets and
   injection payloads vetting flags — which is a step beyond the open
   browsing surface's *metadata* reads (`/content`, `/vetting`,
   `/provenance`). The first line of each controller method is
   `roleService.requireApproverOfSnapshot(...)` (the existing confused-deputy-
   safe resolver for id routes). RoleEnforcementTests gains an
   `APPROVER_SCOPED_READS` classification: denied to the no-role session,
   denied to the auditor (whose charter, GW_0070, enumerates ledger and
   listings, not content), allowed to the owning approver and the admin, and
   denied to an approver of a different marketplace through the bare id.
   With enforcement disabled (the default) every check passes, as everywhere.
   *Rejected branch:* classifying them with the open snapshot-metadata reads
   for symmetry — metadata symmetry loses to content sensitivity; the
   requirement text (GW_0080) states the denial so the SSOT, not a test's
   set membership, owns the rule.

7. **Markdown renders inertly, with no new dependency.** A ~100-line
   `MarkdownView` component maps a safe subset (headings, paragraphs, lists,
   fenced/inline code, bold/italic, blockquotes) to React elements. React
   escapes all text by construction, so embedded HTML in a hostile SKILL.md
   appears *as text*; there is no `dangerouslySetInnerHTML` anywhere and no
   sanitizer to mis-configure. Links render as non-navigating text (the pane
   inspects; it does not invite clicking into a hostile URL). Anything the
   subset does not cover falls back to plain paragraphs — acceptable for an
   inspection surface.
   *Rejected branch:* `marked` + `dompurify` — two dependencies and a
   sanitizer configuration to audit, to produce riskier output (real HTML)
   than the zero-HTML renderer.

8. **Preview pane placement and shape.** Each snapshot card on the detail page
   gains a "Preview files" toggle beside "Show contents", opening a two-pane
   surface: left, the file tree as a scrollable path list (files ordered as
   the tree walk yields them, size chips, binary/truncated badges), with
   SKILL.md and the manifest surfaced first as quick-open chips; right, the
   selected file (Markdown via `MarkdownView` for `.md`, `<pre>` otherwise,
   metadata note for binary, warning note for truncated). A "Diff vs served"
   tab lists the change entries with per-file unified diff text colored by
   +/- line prefix, and states plainly when there is no baseline. Loading,
   empty and error (`role="alert"`) states throughout; all timestamps and
   SHAs render raw per portal convention.

9. **E2E drives the real delta.** The Playwright spec registers a marketplace
   on a dedicated fixture, ingests, approves (now served), then advances the
   fixture upstream with a `git` child process from the spec (the same
   host-git-isolated pattern `run-e2e.sh` uses; the fixture directory is
   passed as `E2E_PREVIEW_UPSTREAM_DIR`), ingests again, and previews the held
   snapshot: tree visible, SKILL.md rendered with its embedded-HTML line shown
   as text, diff naming the modified and added paths against the served
   baseline. The wizard spec asserts the composed command carries the page's
   own origin and `/git/{name}`, mints a token, sees it in the snippet, and
   proves the placeholder is back after close/reopen.

## Risks / Trade-offs

- [A huge upstream tree makes the listing useless] → the 2 000-entry cap with
  an explicit marker keeps the response bounded; a marketplace that large has
  bigger review problems, and the marker says so instead of silently lying.
- [Two SVC methods share SVC_GW_0080 (content contract in PreviewTests,
  enforcement in RoleEnforcementTests' context)] → established pattern
  (SVC_GW_0068 et al. already annotate two methods); reqstool aggregates.
- [The wizard's origin-derived URL is wrong behind a rewriting proxy that
  serves the portal and facade on different hosts] → no such deployment shape
  exists in the docs or config today; if one appears, that is the moment an
  external-URL property earns its existence (documented as the losing branch
  of decision 2).
- [DiffFormatter output for a binary file] → binary entries carry
  `binary: true` and no diff text; the formatter is never asked to render
  binary hunks.

## Migration Plan

Nothing persists, nothing configures: no schema change, no property. Rollback
is revert. The new endpoints are additive; the portal changes are additive to
existing pages.

## Open Questions

None. Downloadable archives, arbitrary snapshot-to-snapshot diffs, and syntax
highlighting are explicitly out of scope (non-goals above).
