# Tasks: portal-shows-invisible-characters

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_APPROVAL_0037, GW_APPROVAL_0038 and SVC_GW_APPROVAL_0040 … SVC_GW_APPROVAL_0043 in `docs/reqstool/`; verify with `reqstool status local -p docs/reqstool` after the build.

## 2. The helper (SVC_GW_APPROVAL_0040)

- [x] 2.1 `lib/visible-controls.test.ts`: every class (bidi embeddings/overrides/isolates, marks, U+061C, zero-width, U+2060–2064, U+FEFF, tag block, other Cf) becomes a named token; normal text, accents, CJK, RTL letters and emoji ZWJ sequences are untouched; a ZWJ outside a pictograph pair is shown (`@SVCs SVC_GW_APPROVAL_0040`). Watch it fail first.
- [x] 2.2 `lib/visible-controls.ts` (`revealControls`, `showControls`, `controlName`) and `components/visible-text.tsx` (`VisibleText`), `@Requirements GW_APPROVAL_0037`. Verify with 2.1.

## 3. Apply to upstream text (SVC_GW_APPROVAL_0041, SVC_GW_APPROVAL_0043)

- [x] 3.1 Component tests: a plugin name, description and skill name in the inventory, a file name in the tree, and a finding location show the token and not the raw character (`@SVCs SVC_GW_APPROVAL_0041`); selecting a file whose name holds a control passes the original path (`@SVCs SVC_GW_APPROVAL_0043`). Watch them fail first.
- [x] 3.2 Use `VisibleText`/`showControls` in the inventory, file tree, file changes, content diff, finding locations, name-collision notice, explorer header and findings list, Markdown and JSON views, diff text, adoption report and marketplace description. Verify with 3.1 and the existing component tests.

## 4. Source view (SVC_GW_APPROVAL_0042)

- [x] 4.1 `source-view-controls.test.tsx` (beside, not in, `source-view.test.tsx`, which open PRs extend): a line with a control shows the token and a written marker that describes the line, with no finding on it; clean lines carry no marker (`@SVCs SVC_GW_APPROVAL_0042`). Watch it fail first.
- [x] 4.2 SourceView line text through `revealControls`, marker beneath, `@Requirements GW_APPROVAL_0038` on the marker. Verify with 4.1 and the existing SourceView tests.
- [x] 4.3 Stories: SourceView with controls, and a plugin name with a control. Verify with `pnpm test:stories` (axe as error).

## 5. Documentation

- [x] 5.1 `reference/portal.md` (how controls are shown) and one sentence in `concepts/vetting.md` at `invisible-characters`. Verify with `mkdocs build --strict`.

## 6. Gates and evidence

- [ ] 6.1 Every AGENTS.md gate fresh after the last code edit, recorded in `evidence.md`; screenshots captured locally.
