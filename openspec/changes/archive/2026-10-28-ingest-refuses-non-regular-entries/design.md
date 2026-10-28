# Design: ingest-refuses-non-regular-entries

## Context

See proposal.md, Why. `IngestionService.serve` already turns a manifest that
breaks policy into a violation, and `ingestLocked` maps any violation to
`rejected`.

## Decisions

### D1 — Reject at ingest, not a vetting finding

A violation at ingest makes the snapshot unapprovable outright. A finding could
be waived, but a waiver cannot make a link mean the same bytes to the vetters
and to a client.

Alternative: record the mode in the vetting snapshot and resolve links inside
the tree. Rejected, because it re-implements checkout semantics, including
links that escape the tree, to support a shape no marketplace needs.

### D2 — Upstream and composite trees are both checked

The upstream commit is checked before its manifest is read. A composite is
checked again after grafting, because grafted external trees come from a
different repository.

### D3 — Only regular and executable files pass

Recursive tree walks report no directories, so anything else (a link, a
gitlink, or a malformed mode) is refused with its path.

## Risks / Trade-offs

- [A marketplace that symlinks shared files stops ingesting] → The violation
  names the path. Copying the file instead of linking it is the fix, and
  nothing is served from a rejected snapshot in the meantime.
