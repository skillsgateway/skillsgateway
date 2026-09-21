# Design: bounded-typed-audit-read

## Context

See `proposal.md — Why`. Two facts shaped the design more than the finding did:

- **`FetchLogRepository.AuditEntry` already exists**, typed and `@Schema`-annotated, and
  the NDJSON export has published it all along. The browse read did not need a new
  shape; it needed to stop having none.
- The portal's audit table reads `ts`, `event`, `principal`, `marketplace` and `sha` —
  every one of them on that record. Nothing consumes `actor_type` or `credential_kind`,
  which the untyped read leaked.

## Decisions

### 1. Newest first, cursored on `id`

Two properties, and only the second is about performance.

**Newest first** because this read exists for a person, not a pipeline. The export
ascends from a cursor because a SIEM resumes where it stopped; someone opening the
audit page is asking what just happened. Same rows, same record, opposite ends.

**Cursored rather than offset** because `fetch_log` is append-only at the newest end.
An `OFFSET 20 LIMIT 20` page shifts by one row for every entry appended between the two
requests, so a reader paging backwards silently re-reads or skips. `WHERE id < :before`
cannot: the window is anchored to a row, not to a count.

That is also why `nextBefore` is the page's own oldest id rather than a page number.

### 2. A short page ends the walk

`nextBefore` is null exactly when the page came back shorter than the limit. Returning
the last id unconditionally would invite a caller to request an empty page forever, and
"there is no more" is information the server has and the client otherwise has to infer
from an empty response.

The trade is one wasted request when the ledger's size is an exact multiple of the page
size: the last full page still carries a cursor, and the page after it is empty. That
is cheaper than the alternative, which is a count query on the table this whole change
exists to stop scanning.

### 3. The endpoint moves to `AuditController`

The path does not change — `AuditController` already maps `/api/v1/audit`. What changes
is that the ledger's three surfaces (browse, export, sinks) are in one file, and
`AdminController` does not gain a `SkillsGatewayProperties` dependency it has no other
use for.

### 4. `list()` stays, with a warning on it

Ten test classes read the whole ledger to assert over it, which is reasonable at a few
dozen rows. Deleting the method would have meant rewriting them all to page, for no
gain — the defect was an unbounded *request path*, not an unbounded method.

So it stays, and its javadoc says what it is: **not for a request path**, and that the
endpoint which used it is why `GW_AUDIT_0008` exists. A future author reaching for it
from a controller reads that first.

*Alternative considered:* move it to a test fixture. Rejected — it would need the same
SQL and the same mapper, and a second copy of a query is how the two drift.

### 5. The page bound is the export's

`audit-export.default-page-size` / `max-page-size` already bound reads of this table.
Adding `audit.browse.*` would be two settings that must agree about one table's read
size, and the stop rule asks what a new leaf buys. Nothing here.

## Risks / Trade-offs

- **The portal now shows one page where it showed everything** → it showed the *newest*
  activity either way, and its filters were always client-side over what it had. A
  "load older" control is the honest follow-up and is out of scope, said so in the
  proposal rather than left implied.
- **A client relying on the bare array breaks** → intended; pre-1.0, and the same
  release moves every path anyway.
- **`nextBefore` invites building a full-table walk** over the table this change exists
  to stop scanning → the export endpoint is the supported way to read the whole ledger,
  and it withholds entries younger than the settling lag, which this read does not need
  to because it never claims completeness.

## Migration Plan

None. No schema, no configuration, no stored data.
