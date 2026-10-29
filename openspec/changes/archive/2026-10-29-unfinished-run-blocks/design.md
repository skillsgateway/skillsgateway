# Design: unfinished-run-blocks

## Context

See proposal.md, Why. `VettingRepository.startRun` inserts the run with outcome
`blocked` and no `finished_at`. Each verdict is recorded in its own
transaction, and `finishRun` stamps the outcome at the end.
`WaiverEvaluation.evaluate` recomputed the effective outcome from the recorded
verdicts alone.

## Decisions

### D1 — Fail closed on `finished_at`, at the one evaluation every gate uses

The approval gate, the re-vet comparison and the portal badge all read
`WaiverEvaluation.evaluate`. Checking `finished_at` there covers a run that
died, a run still running, and any future way a run can stop. Fixing only the
NUL would leave every other way unguarded.

### D2 — Replace NUL with U+FFFD at the insert

The record stays legible: the reviewer sees where the character was. NUL is
the only character PostgreSQL text refuses, and the vetters already scan
`String`s, so nothing upstream of the insert changes.

### D3 — The ingest still succeeds

With the record fixed, the run finishes and the snapshot is held and blocked
with its findings, rather than the ingest failing on a database error.

## Risks / Trade-offs

- [A reviewer approving while a run is in progress gets "blocked"] → That is
  the gate saying the evidence is not in yet. The run finishes in seconds.
