## 1. Prove the failure first

- [ ] 1.1 Install the lock file of #646 (axe-core 4.14.0) in the working tree without committing it, run `pnpm test:stories`, and record the 27 failing stories in five files as the baseline
- [ ] 1.2 Add the property assertion of Decision 4 (accessible name contains `textContent`) to the stories or tests for each of the five controls, and see it fail before the fix

## 2. Snapshot file tree

- [ ] 2.1 Make `DirectoryMarker` and `FileMarker` return their text through functions the row also uses to build its label (visible text first, meaning in parentheses)
- [ ] 2.2 Update `snapshot-file-tree.stories.tsx` and `snapshot-files.test.tsx` to the new names

## 3. Vetting flow, report and estate table

- [ ] 3.1 Drop the `aria-label` from the flow stage buttons in `vetting-flow.tsx`; update `vetting-flow.stories.tsx`
- [ ] 3.2 Name the re-run button `<visible text> (snapshot N)` in both of its states in `vetting-report.tsx`; update `chain-staleness.stories.tsx` and `vetting-report.test.tsx`
- [ ] 3.3 Name the clear button `<visible text> (<marketplace>)` in `pages/vetting.tsx`; update `vetting.test.tsx` and `vetting.stories.tsx`, leaving the confirmation message in `lib/vetting-overrides.ts` as it is

## 4. The e2e spec

- [ ] 4.1 Update the four queries in `e2e/portal.spec.ts` that name these controls (the clear and re-run buttons, and any file-tree row), and re-run `pnpm e2e`

## 5. Review and documentation

- [ ] 5.1 Run `/impeccable audit` and `/impeccable harden` on the affected surfaces; fix the findings or dismiss them in the PR body with a reason, including the open question on the bare file count
- [ ] 5.2 `grep` `docs/manual/` for the old accessible names and update any page that quotes them; state the result either way
- [ ] 5.3 Mark the PR `no-screenshots`: nothing visible changes

## 6. Gates and evidence

- [ ] 6.1 Run, in the foreground and fresh after the last edit: `./mvnw clean verify`, `pnpm test:stories` (with the lock file of 1.1 and with the repository's own), `pnpm e2e`, `reqstool status local -p docs/reqstool`, `openspec validate --all --strict`, `mkdocs build --strict`
- [ ] 6.2 Write `evidence.md` with the commands, result tails and the commit SHA, and put its summary in the PR body
- [ ] 6.3 Archive the change with `/opsx:archive` as the PR's last commit, using `--skip-specs` (`skip_specs: true`; the active change has no deltas, so `openspec validate` rejects it until it is archived, which is why archiving must be the last commit)
- [ ] 6.4 After merge, ask Renovate to rebase #646 and confirm its Storybook tests pass
