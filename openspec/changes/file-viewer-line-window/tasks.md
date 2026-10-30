# Tasks: file-viewer-line-window

## 1. Requirements (reqstool)

- [ ] 1.1 Add GW_APPROVAL_0036, SVC_GW_APPROVAL_0037, SVC_GW_APPROVAL_0038 and SVC_GW_APPROVAL_0039 in `docs/reqstool/`; verify with `reqstool status local -p docs/reqstool`.

## 2. API window (SVC_GW_APPROVAL_0037, SVC_GW_APPROVAL_0038)

- [x] 2.1 `SnapshotFileWindowTests` (integration): a 300 KiB file with numbered lines read with `line=2000` returns text whose first line is line `firstLine`, contains line 2000, is at most 128 KiB, and starts at a line boundary; without `line` the response is the head with `firstLine` 1; a small file with any `line` is whole with `firstLine` 1; a line past the end returns the head; a blank path is 400 and a no-role session gets 403 (`@SVCs SVC_GW_APPROVAL_0037`, `SVC_GW_APPROVAL_0038`). Watch it fail first.
- [x] 2.2 `SnapshotPreviewService.read` windowing and `FileContent.firstLine`; controller `line` and blank-path 400 (`@Requirements GW_APPROVAL_0036`). Verify with 2.1.
- [x] 2.3 Regenerate `openapi.json` and `types.gen.ts`; verify with `OpenApiContractTests`.

## 3. Portal (SVC_GW_APPROVAL_0039)

- [x] 3.1 `snapshot-files.test.tsx` (or the explorer's test): a truncated file with a finding at a line past the head shows the finding as a button; following it requests `line=N` and shows line N marked, numbered from `firstLine` (`@SVCs SVC_GW_APPROVAL_0039`). Watch it fail first.
- [x] 3.2 `useSnapshotFile(line)`, `FileContent`, `FileFindings` and `SourceView(firstLine)`, with the truncation notice naming the lines shown (`@Requirements GW_APPROVAL_0036`). Verify with 3.1 and the existing source-view tests.

## 4. Documentation

- [x] 4.1 `reference/api/marketplaces.md` (`line`, `firstLine`, 400) and `reference/portal.md` (Contents view: following a finding beyond the head). Verify with `mkdocs build --strict`.

## 5. Gates and evidence

- [ ] 5.1 Every AGENTS.md gate fresh, recorded in `evidence.md`; before/after screenshots for the PR body.
