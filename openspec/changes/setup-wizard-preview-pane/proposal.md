# Client setup wizard and reviewer preview pane

GitHub issue #15.

## Why

Two personas hit the portal and then leave it for the wrong reason. A consumer
who found a marketplace still has to hand-assemble the facade URL, the
`claude plugin marketplace add` command, and a git credential configuration
from prose in the manual — every transcription error lands as a support
question. And a reviewer deciding a held snapshot sees the vetting chain's
verdicts but not the content itself: the actual SKILL.md, the manifest, the
file tree, and above all what *changed* against the snapshot consumers are
currently receiving. Today that means cloning quarantine content out of band,
which the model forbids, or approving on a summary.

## What Changes

- **Client setup wizard** (portal only, no new API): a "Set up a client"
  affordance on the marketplace detail page opens a wizard that composes, for
  that marketplace, the `claude plugin marketplace add <origin>/git/<name>`
  command, the git credential-helper configuration block, and the PAT usage
  snippet — all derived client-side from `window.location.origin`, no new
  configuration property. The wizard can mint a PAT through the existing
  show-once token flow; the cleartext is substituted into the snippets only
  while the wizard stays open and is never re-displayed.
- **Snapshot inspection API** (read-only): `GET /api/snapshots/{id}/files`
  (file tree of the pinned commit), `GET /api/snapshots/{id}/file?path=`
  (one file's content), `GET /api/snapshots/{id}/diff` (added/modified/removed
  paths and per-file text diffs against the marketplace's currently served
  commit). All reads resolve strictly through JGit tree walks over the
  quarantine repository at the snapshot's pinned SHA — no filesystem paths, so
  no traversal surface — with a per-file size cap carrying an explicit
  truncation marker, binary detection returning metadata instead of bytes, and
  a bounded tree listing.
- **Reviewer preview pane** (portal): on the marketplace detail page, each
  snapshot gains a preview surface with file-tree navigation, inert
  client-side Markdown rendering of SKILL.md and the manifest (never
  `innerHTML`; embedded HTML is shown as text), truncation/binary indicators,
  and a diff view against the served baseline.
- **Boundary statement**: this is inspection, not execution, and it creates no
  new path from quarantine to the facade. The three endpoints live on the
  OIDC web surface under `/api/**`; the facade still opens only published
  repositories, and nothing in this change touches `/git/**` or
  `ApprovalService`'s publication step. While role enforcement is enabled the
  reads are privileged: admin or an approver of the snapshot's marketplace —
  they expose held quarantine content, which is exactly what the approver
  role scopes.

## Capabilities

### New Capabilities

- `snapshot-preview`: the inspection reads over a snapshot's pinned commit and
  the diff against the served baseline, with their caps and denial rules
  (GW_0080, GW_0081), and the portal preview pane presenting them (GW_0082).

### Modified Capabilities

- `admin-portal`: the client setup wizard on the marketplace detail page
  (GW_0079). Existing portal requirements are untouched.

## Impact

- **Backend**: new `preview` package (`SnapshotPreviewService`,
  `SnapshotPreviewController`); no schema change, no new tables, no new
  configuration property. `GitStorage` is used as-is (quarantine for content,
  `publishedIfServing` for the baseline tip).
- **API**: the three reads above, gated first-line by
  `roleService.requireApproverOfSnapshot(...)` and classified in
  RoleEnforcementTests as approver-scoped reads (a new classification set:
  denied to a no-role session and to an auditor, allowed to the owning
  approver and to an admin). OpenAPI snapshot and generated TS types
  regenerate.
- **Portal**: `components/setup-wizard.tsx`, `components/snapshot-preview.tsx`
  (+ a small inert Markdown renderer), wired into `pages/marketplace-detail.tsx`;
  new queries and typed MSW handlers; component tests; two Playwright e2e
  specs (`SVC_GW_0079`, `SVC_GW_0082`) driving a real held snapshot's preview
  and diff and the wizard's show-once behavior.
- **Docs**: `reference/api/marketplaces.md` (new endpoints),
  `reference/portal.md` (wizard + preview pane), `guides/consuming-skills.md`
  (the wizard as the paved path), `guides/approving-snapshots.md` (preview
  before deciding). No new pages, no nav change.
- **Traceability**: GW_0079–GW_0082 + SVC_GW_0079–SVC_GW_0082 (GW_0073/0074
  are reserved by one in-flight change; GW_0075–0078 are taken by another).
