# Proposal: an unfinished vetting run blocks approval

## Why

A vetting run is inserted first and records its verdicts one at a time; it
stamps `finished_at` last. The approval gate evaluated whatever verdicts the
latest run had recorded and never checked that the run had finished. So a run
that stopped part-way was judged on the verdicts it recorded before it stopped,
and if those cleared, the snapshot could be approved. The same applies to a run
still in progress. `concepts/vetting.md` already said a snapshot "whose run died
halfway" is blocked; the code did not do that.

One way a run stopped part-way was in the content itself. Findings quote
upstream text, and PostgreSQL text cannot hold a NUL. A hook command carrying a
NUL, written as a JSON `\u0000` escape so the file is still text, made the
insert of its own finding fail, and the run stopped before that verdict was
stored.

## What Changes

- New `GW_VETTING_0067 — A vetting run that has not finished blocks approval`.
  `WaiverEvaluation.evaluate` returns blocked for a run with no `finished_at`,
  whatever its recorded verdicts and the active waivers say.
- New `GW_VETTING_0068 — Upstream text with a NUL character is recorded in
  vetting results`. `VettingRepository.recordVerdict` stores a verdict's detail
  and report link, and a finding's id, location and message, with each NUL
  replaced by U+FFFD.
- New `SVC_GW_VETTING_0070`: an unfinished run whose only verdict passes is
  blocked, and the same run, finished, is clear.
- New `SVC_GW_VETTING_0071`: a hook command that downloads and runs code and
  holds a NUL is recorded blocked, with the finding, and its approval is refused.
- Not **BREAKING**. An approval during a run in progress is now refused until
  the run finishes. An administrator's override still applies.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0067` and `GW_VETTING_0068`.

## Impact

- `WaiverEvaluation.evaluate` and `VettingRepository.recordVerdict`.
- Docs: `concepts/vetting.md`, the blocked cases and the outcome table.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
