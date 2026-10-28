# Design: bounded-diff

## Decisions

### D1 — No fallback, rather than a size cap

The demonstrated payload is below the 128 KiB output cap, so a size cap would
have to sit far below it to help, and would then hide ordinary files from
review. A fallback-free histogram diff is linear in practice, and gives the
same edits wherever histogram succeeds, which is almost every real file.
