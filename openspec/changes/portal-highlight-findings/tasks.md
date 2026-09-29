# Tasks: portal-highlight-findings

Portal-only, and not a trust boundary, so this follows the normal test-first
loop rather than old-coder. Each new test is seen failing before its code
exists. `.claude/skills/design-conventions` governs the UI, and Impeccable runs
on the changed surfaces.

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0029 and GW_APPROVAL_0030, with SVC_GW_APPROVAL_0029
  and SVC_GW_APPROVAL_0030 (automated tests), to `docs/reqstool/`. Verify with
  `openspec validate portal-highlight-findings --strict`.

## 2. Reading findings per file (D1)

- [x] 2.1 `lib/file-findings.test.ts` (`@SVCs SVC_GW_APPROVAL_0029`):
  - `parseLocation` on `a/b.md:12`, `a:b/c.sh:3` (last colon), `a/b.md`
    (no line), `a/b.md:x` (no line) and `C:\x` (no line).
  - `findingsByPath` groups by path across verdicts, carries the vetter, marks
    a finding waived only when vetter, rule and location all match
    `suppressed`, and keeps a line-less finding.
  - `highestSeverity` orders critical, high, medium, low, info.

  Watch it fail.
- [x] 2.2 Implement `lib/file-findings.ts` (JSDoc `@Requirements GW_APPROVAL_0029`).
  Make 2.1 green.

## 3. Source view (D2, D3, D4)

- [x] 3.1 `components/source-view.test.tsx` (`@SVCs SVC_GW_APPROVAL_0029`):
  - Every line is numbered.
  - A marked line shows each of its findings as text below it (severity,
    vetter, rule, message), and a line with two findings shows both.
  - A waived finding says "waived by … until …".
  - The marked line is described by its notes (`aria-describedby`).
  - A finding beyond a truncated view is still shown, with its line.

  Watch it fail.
- [x] 3.2 Implement `components/source-view.tsx`. Make 3.1 green.
- [x] 3.3 `snapshot-explorer` tests (`@SVCs SVC_GW_APPROVAL_0029`):
  - A file with findings opens in the source view, with the summary "2
    findings in this file".
  - Activating a summary entry focuses its line.
  - A Markdown file with findings offers Source / Rendered.
  - A file without findings keeps today's rendering: Markdown rendered, JSON
    formatted.

  Watch it fail, then wire it into `FileContent` and `SnapshotExplorer`
  (`@Requirements GW_APPROVAL_0029`).
- [x] 3.4 Tree marker (D7): a `snapshot-file-tree` test for "2 high" on a file
  row, and none on a directory. Implement.
- [x] 3.5 Stories for `SourceView`: plain, marked (every severity), two
  findings on one line, waived, beyond-truncation, long lines, and dark mode.
  Update the explorer story with findings. Verify with `pnpm test:stories`
  (axe as error). (Done as `SourceView` stories plus `FileTree` `WithFindings`
  and `WithFindingsDark`. The explorer has no story; its component tests
  cover it.)

## 4. Address and links (D5, D6)

- [ ] 4.1 Tests (`@SVCs SVC_GW_APPROVAL_0030`):
  - `marketplace-detail` reads and writes `line`.
  - Choosing another file clears it.
  - `snapshot-files` does the same with `?line=`.
  - An addressed line is focused once the file loads.
  - Copy link includes the line.

  Watch them fail, then implement (`@Requirements GW_APPROVAL_0030`).
- [ ] 4.2 `VettingReport` and `VettingFlow` tests (`@SVCs SVC_GW_APPROVAL_0030`):
  - With `locationHref`, every location is a link to
    `?snapshot=&tab=contents&path=&line=`, including each location of a group.
  - Without it, as in the approval dialog, they stay text.
  - A location without a line links to the file.

  Watch them fail, then implement, and pass `locationHref` from
  `snapshot-card.tsx`.
- [ ] 4.3 Playwright e2e `highlight.spec.ts`, against the real jar
  (`@SVCs SVC_GW_APPROVAL_0029, SVC_GW_APPROVAL_0030`, snake_case titles):
  - Register a marketplace whose plugin's hook pipes a download to `sh`.
  - Open its review and follow the finding's location from the Vetting tab.
  - Assert that the Contents tab shows the file, the line is focused and
    marked, the description text is visible, and the address carries `line`.

## 5. Docs (same PR)

- [ ] 5.1 `reference/portal.md`: the Contents tab (the source view, markers,
  the summary, the view toggle, tree markers), the `line` address parameter,
  and links in the Vetting tab. `guides/approving-snapshots.md`: reading a
  finding in context. Verify with `mkdocs build --strict`.

## 6. Design harness

- [ ] 6.1 `/impeccable audit` and `/impeccable harden` on the Contents tab and
  the Vetting tab. Fix each finding or dismiss it with a reason, and record
  both in the PR body.

## 7. Gates and archive

- [ ] 7.1 A fresh run of all six gates after the last code edit. Write
  `evidence.md` with the commands, the result tails and the SHA.
- [ ] 7.2 Archive with the synced `openspec/specs/**` as the final commit.
