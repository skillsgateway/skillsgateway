# Design: withdrawal-survives-reject

## Context

See proposal.md, Why. `decide` clears `revoked_at`, `revoked_by`,
`revoked_kind` and `violation` on every transition. That is right when a
reversal re-approves, and when a re-vetting revocation is rejected. It is wrong
for a reject of an administrative withdrawal.

## Decisions

### D1 — Refuse, rather than reject while keeping the marker

Two alternatives were considered, and both would spread the withdrawal over two
states:

- keep `revoked_kind` on a rejected row;
- teach `administrativeRevocationOf` and the retention exemption to read
  rejected rows.

Refusing keeps one state that means "withdrawn", and the only way out stays
the reversal.

### D2 — In `decide`, beside the deleted check

`decide` is the one place a state moves to approved or rejected. The refusal
sits with the other "this row may not be decided like this" checks and uses
the same 409 mapping.

## Risks / Trade-offs

- [A portal that offers reject on a withdrawn snapshot now gets 409] → The
  message names the administrator's withdrawal and the reversal that lifts it.
