# Tasks: unfinished-run-blocks

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_VETTING_0067, GW_VETTING_0068, SVC_GW_VETTING_0070 and SVC_GW_VETTING_0071 in `docs/reqstool/`.

## 2. Fail closed (SVC_GW_VETTING_0070)

- [x] 2.1 `UnfinishedRunTests`: an unfinished run whose only verdict passes is
  blocked, and the same run, finished, is clear. Watch it fail first.
- [x] 2.2 `WaiverEvaluation.evaluate` blocks a run with no `finished_at`
  (`@Requirements GW_VETTING_0067`).

## 3. Record NUL (SVC_GW_VETTING_0071)

- [x] 3.1 `NulInFindingTests`: a hook command that downloads and runs code and
  holds a NUL is recorded blocked, with U+FFFD in the message, and its approval
  is refused. Watch it fail first.
- [x] 3.2 `VettingRepository.recordVerdict` stores text with NUL replaced
  (`@Requirements GW_VETTING_0068`).

## 4. Documentation

- [x] 4.1 `concepts/vetting.md`: the blocked cases and the outcome table.
  Verify with `mkdocs build --strict`.

## 5. Gates and evidence

- [ ] 5.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
