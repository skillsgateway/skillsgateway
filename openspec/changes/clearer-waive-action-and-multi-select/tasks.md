# Tasks: clearer-waive-action-and-multi-select

## 1. Requirements (reqstool)

- [x] 1.1 Revise GW_VETTING_0044 (the waive label names the rule, and the form
  states its coverage before submit) and SVC_GW_VETTING_0044 to revision 0.5.0.
  Add GW_VETTING_0061 (several blocking groups waived in one action with one
  justification and expiry) and SVC_GW_VETTING_0061. Verify with
  `openspec validate clearer-waive-action-and-multi-select --strict`.

## 2. Clearer single waive (SVC_GW_VETTING_0044)

- [x] 2.1 `vetting-report.test.tsx`: the button text names the rule
  (`Waive concealment-instruction at 4 locations…`); the form states the
  coverage for each scope choice; the submit button carries the count
  ("Record waiver for 4 findings"); the rule scope counts every finding of the
  rule in the run. Watch each fail first.
- [x] 2.2 Implement in `GroupRow` and `WaiveForm`, keeping the
  name-collision use working (`name-collision-notice` tests unchanged and
  green). Update `@Requirements GW_VETTING_0044`. Verify that 2.1 is green.

## 3. Multi-select waive (SVC_GW_VETTING_0061)

- [x] 3.1 `vetting-report.test.tsx` (`@SVCs SVC_GW_VETTING_0061`): checkboxes
  on blocking groups with content, in two verdicts and two rules; selecting
  them shows "2 groups, 5 findings selected"; the form is disabled until a
  justification and valid expiry are given (whitespace stays disabled); submit
  posts one group waiver per selected group with the same justification and
  expiry; a refused post is reported by rule and stays selected. Watch it fail
  first.
- [x] 3.2 Implement the selection state, selection bar and bulk form in
  `vetting-report.tsx` (`@Requirements GW_VETTING_0061`). Verify that 3.1 is
  green.
- [x] 3.3 Story states for the selection bar and bulk form, verified with
  `pnpm test:stories` (axe as error).
- [x] 3.4 e2e: extend the waiver spec with a two-rule bulk waive against the
  real gateway (`@SVCs SVC_GW_VETTING_0061`), verified with `pnpm e2e`.

## 4. Docs and screenshots

- [x] 4.1 Update the portal manual's waiver section (label, coverage count,
  multi-select, partial-failure behaviour). Verify with
  `mkdocs build --strict`.
- [ ] 4.2 Take before and after screenshots for the PR body
  (`.claude/skills/ui-screenshots`), run `/impeccable audit` and `harden` on
  the vetting surface, and fix or dismiss each finding in the PR body.

## 5. Gates and evidence

- [ ] 5.1 Run every gate fresh after the last code edit and record it in
  `evidence.md` with the commit SHA.
