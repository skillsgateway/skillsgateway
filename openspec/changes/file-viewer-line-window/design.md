# Design: file-viewer-line-window

## Context

See proposal.md, Why. `SnapshotPreviewService.read` reads up to `MAX_TEXT_BYTES`
(128 KiB) from the blob's stream and reports `truncated`. Blobs are bounded at
ingest, so one more pass over the stream is affordable.

## Decisions

### D1 — A window chosen by line, not a byte offset

The reviewer arrives with a line number: a finding's location, or `&line=N` in
the portal URL. So `line` is the parameter, and the server does the line
counting. With a byte offset, the browser would have to know the byte position
of a line it has never seen.

### D2 — Two streaming passes, bounded memory

The first pass counts newlines up to the requested line and keeps the start
offsets of recent lines in a deque, trimmed to the half-cap before that line.
The window starts at the earliest kept line start, so the line has up to 64 KiB
of context before it and the rest after it. The second pass skips to that
offset and reads at most the cap. Neither pass holds more than the cap. A line
past the end of the blob returns the head with `firstLine` 1, and the portal
already says "beyond the part shown" for that case.

Alternative: raise the cap. Rejected. The cap protects the reviewer's browser
from a hostile upstream, and any cap leaves some line beyond it.

### D3 — The portal asks for the focused line

`useSnapshotFile` takes the focused line and sends it only once the reviewer has
followed a line. A file within the cap returns the same text whatever line is
asked for, so the query key changes but the content does not. A finding outside
the shown range becomes a button that focuses its line. That reuses the path an
in-range finding already takes.

### D4 — A blank path is a bad request

`TreeWalk.forPath` throws on an empty path, which surfaced as a 500. The
controller refuses a blank `path` with 400 before resolving the snapshot. The
access check stays first, so a session with no role still gets 403.

## Risks / Trade-offs

- [A single line longer than the cap] → The window starts at that line and is
  cut at the cap, so the reviewer sees the line's start with `truncated` set.
  Same behaviour as the head today.
