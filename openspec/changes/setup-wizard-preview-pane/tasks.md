# Tasks: setup-wizard-preview-pane

## 1. Traceability (SSOT first)

- [ ] 1.1 Add GW_0079–GW_0082 to `docs/reqstool/requirements.yml`
      (setup wizard; snapshot file inspection; diff vs served baseline;
      preview pane). GW_0073/0074 and GW_0075–0078 are taken by other
      in-flight changes.
- [ ] 1.2 Add SVC_GW_0079–SVC_GW_0082 to
      `docs/reqstool/software_verification_cases.yml`.

## 2. Backend

- [ ] 2.1 `preview/SnapshotPreviewService`: tree listing (path, size, binary;
      2 000-entry cap with marker), file read (128 KiB cap, truncation marker,
      binary detection, tree-addressed 404), diff vs the served tip resolved
      via `GitStorage.publishedIfServing` (added/modified/removed, per-file
      unified text diff under the same caps, `baselineSha` null + all-added
      when nothing is served). `@Requirements` GW_0080, GW_0081.
- [ ] 2.2 `preview/SnapshotPreviewController`: the three GET routes, each with
      `roleService.requireApproverOfSnapshot(...)` as the first line, OpenAPI
      annotations, `@Schema` on DTOs.

## 3. API artifacts

- [ ] 3.1 Regenerate `src/main/frontend/openapi.json` and `types.gen.ts`.

## 4. Tests

- [ ] 4.1 `PreviewTests` (SVC_GW_0080): tree lists the pinned commit's paths;
      text file content; oversized file truncated with marker; binary file as
      metadata without text; absent and traversal-shaped paths are 404.
- [ ] 4.2 `PreviewTests` (SVC_GW_0081): approve, advance upstream
      (modify + add + remove), ingest; the held snapshot's diff names the
      served baseline and exactly the changed paths with text diffs; a
      never-served marketplace's diff reports no baseline and all paths added.
- [ ] 4.3 RoleEnforcementTests: new `APPROVER_SCOPED_READS` classification —
      denied to the no-role session and the auditor, allowed to the owning
      approver and the admin, denied cross-marketplace through the bare id
      (SVC_GW_0080).

## 5. Portal

- [ ] 5.1 Queries `useSnapshotFiles` / `useSnapshotFile` / `useSnapshotDiff`;
      typed MSW handlers.
- [ ] 5.2 `components/markdown-view.tsx`: inert React-element Markdown subset,
      zero `dangerouslySetInnerHTML`; component test proving embedded HTML
      renders as text.
- [ ] 5.3 `components/snapshot-preview.tsx`: file tree, file viewer
      (markdown/text/binary/truncated), diff view, loading/empty/error states;
      wired into `pages/marketplace-detail.tsx`; JSDoc `@Requirements`
      GW_0080–GW_0082; component tests.
- [ ] 5.4 `components/setup-wizard.tsx` on the marketplace detail page:
      origin-derived add command, credential-helper block, PAT snippet,
      in-wizard token mint via the existing flow, show-once held on
      close/reopen; disabled-until-valid form rules; accessible copy buttons;
      JSDoc `@Requirements` GW_0079; component tests.
- [ ] 5.5 Playwright e2e: wizard spec (SVC_GW_0079) and preview/diff spec
      (SVC_GW_0082, real held snapshot vs served baseline via the
      `E2E_PREVIEW_UPSTREAM_DIR` fixture).
- [ ] 5.6 `/impeccable audit` + `harden` on the changed surface; findings
      fixed or dismissed in the PR body.

## 6. Documentation

- [ ] 6.1 `reference/api/marketplaces.md`: the three preview endpoints.
- [ ] 6.2 `reference/portal.md`: setup wizard and preview pane sections.
- [ ] 6.3 `guides/consuming-skills.md`: the wizard as the paved path;
      `guides/approving-snapshots.md`: preview before deciding.

## 7. Gates and evidence

- [ ] 7.1 `./mvnw clean verify`
- [ ] 7.2 `(cd src/main/frontend && pnpm e2e)`
- [ ] 7.3 `reqstool status local -p docs/reqstool` → PASS
- [ ] 7.4 `openspec validate --all --strict`
- [ ] 7.5 `mkdocs build --strict`
- [ ] 7.6 `evidence.md` with the final commit SHA.
