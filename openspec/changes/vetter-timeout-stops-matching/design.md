# Design: vetter-timeout-stops-matching

## Context

See proposal.md, Why. `VettingService.runGuarded` already turns a timeout into
an `ERROR` verdict and cancels the future. Its constructor comment accepted the
abandoned thread as the cost of in-process vetters.

## Decisions

### D1 — Make the input interruptible, not the engine

`Matcher` reads its input only through `CharSequence.charAt`, so a wrapper that
checks `Thread.isInterrupted()` there stops any pattern, however it
backtracks. The exception is a `CancellationException`, which a cancelled
`FutureTask` discards.

Alternatives considered:
- A fixed-size executor alone. Rejected, because it turns abandoned CPU into a
  starved vetting queue.
- Rewriting every superlinear pattern. That bounds only the patterns someone
  found, so here it is done only for the section header, whose fix is a
  one-line possessive form.

### D2 — Every content match site, mechanically

Each `PATTERN.matcher(x)` over content in the vetter classes and in
`HookModules` becomes `PATTERN.matcher(InterruptibleText.of(x))`. Path-only
matchers are wrapped too: it costs nothing and leaves no site to decide about.
Outside a vetter thread, nothing interrupts the caller, so request-path readers
behave as before.

### D3 — Requirement

`GW_VETTING_0002` says a timeout blocks, but not that the work stops. That is
the new `GW_VETTING_0062`.

## Risks / Trade-offs

- [One volatile read per character matched] → It is negligible beside the
  pattern work, and the full suite's timing is unchanged.
- [`DOWNLOAD_THEN_RUN` still backtracks superlinearly until the timeout] →
  That is now bounded by one time limit per vetter run. Rewriting it belongs
  with the line-number and per-root cost fixes.
