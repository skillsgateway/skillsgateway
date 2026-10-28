# Proposal: an approver's reach is the live marketplace, not its name's history

## Why

Two approver-scoping gaps:
- **Re-registered names.** `RoleService` resolves a snapshot or waiver id to its
  marketplace *name*, and a grant matches by name. A removed marketplace's name
  can be registered again, so the successor's approver passes the check on the
  predecessor's held content and waivers.
- **Fetchers report.** `FetchLogRepository.fetchersOf` filters by commit only,
  so an approver of marketplace A reads who fetched the same commit through B.

## What Changes

- Snapshot and waiver ids resolve to a name only while their marketplace is the
  live holder of it. A removed predecessor resolves to no name, so only an
  administrator reaches it.
- `fetchersOf` takes the marketplace id, and both the report and re-vetting's
  affected list bind it.
- New `GW_AUTH_0056 — An approver's reach is the live marketplace, not its
  name's history`, verified by `SVC_GW_AUTH_0056`.
- Docs: the fetchers reference and the name-reuse note in the registration
  guide.
- Not **BREAKING**.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-roles`: adds `GW_AUTH_0056`.

## Impact

- `RoleService`, `FetchLogRepository.fetchersOf`, `RevetService`.
