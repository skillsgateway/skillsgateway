# Proposal: a snapshot diff's work is bounded, not only its output

## Why

`SnapshotPreviewService.diff` caps its output at 128 KiB, but computes the diff
with JGit's default `HistogramDiff`, which falls back to Myers for regions of
heavily repeated lines. A 128,000-byte file pair, built so that most lines
repeat, took about 30 s per approver view, and about 12 s even with the summary
cached.

## What Changes

- The diff uses a `HistogramDiff` with no fallback. A region it cannot align
  becomes one replacement edit, in milliseconds.
- New `GW_INGEST_0073 — A snapshot diff's work is bounded, not only its
  output`, verified by `SVC_GW_INGEST_0073`.
- Docs: `guides/approving-snapshots.md`.
- Not **BREAKING**. For ordinary files the diff is the same; for adversarial
  ones it is coarser.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-preview`: adds `GW_INGEST_0073`.

## Impact

- `SnapshotPreviewService.diff`.
