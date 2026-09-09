# Proposal: derive-privileged-reads

## Why

`RoleEnforcementTests` derives every non-`GET` route under `/api` from the
running application's own `RequestMappingHandlerMapping` and fails if the
hand-classified sets do not equal it. A mutation added without a deliberate
classification — and a matching `require*` call — fails the suite instead of
shipping unprotected. It is the best control in the codebase.

**It stopped at `GET`.** Privileged reads were a hand-written list, so a read
that nobody thought to add was classified by silence, and silence meant open.

That is not hypothetical. `GET /api/snapshots/{id}/fetchers` — the blast-radius
report, naming who fetched a snapshot — shipped with no guard at all, and the
suite asserted a 200 for a session holding no role, pinning the omission in
place. It was found by reading the code, which is the way of finding things this
control exists to replace.

## What Changes

- **Reads are derived too.** The route-table walk is parameterised by HTTP
  method, and the read set is asserted complete against the same table.
- **Every read is classified into one of four named sets**, so a new one cannot
  be admitted by silence:
  - `PRIVILEGED_READS` — the ledger and operational listings; auditor or admin.
  - `APPROVER_SCOPED_READS` — the three preview reads and the blast-radius
    report; an approver of that snapshot's marketplace, or an admin.
  - `OWNER_SCOPED_READS` — `/api/me` and a session's own tokens.
  - `OPEN_READS` — the browsing surface.
- **The walk asserts each class**, replacing a hand-written sequence of ten
  `isOk()` calls that named routes one at a time and could not notice an
  eleventh. Two routes gain coverage that had none: `/licenses` and
  `/four-eyes`.
- **The auditor walk gains a refusal.** An auditor is not an approver, so the
  marketplace-scoped reads are shut to one. That pins the decision taken for the
  blast-radius report rather than leaving it as an absence.
- **The read walks address a real snapshot.** A 404 and a 403 are both refusals
  and only a real resource tells them apart — and an open read cannot answer 200
  about a snapshot that is not there.

## Capabilities

### New Capabilities

_None._ **No production code changes.** Nothing the gateway does is different
after this change; what is different is that the next unguarded read fails the
build instead of waiting to be read.

### Modified Capabilities

**admin-roles.** GW_AUTH_0010 — Scoped admin roles with deny-by-default
enforcement is unchanged in text: it already says enforcement refuses every
audit-ledger and operational-listing read to a session holding no applicable
role, and already calls deny-by-default the shape. SVC_GW_AUTH_0010 is amended,
because what verifies it has genuinely grown — reads are now derived rather than
listed, and each is classified.

## Impact

None at runtime. The one behavioural claim this change makes is about the build:
a read endpoint added without a classification now fails `RoleEnforcementTests`
rather than passing it.
