# Design: personal-routes-exclude-machine

## Decisions

### D1 — Filter in the queries

Every owner-scoped path goes through these three queries, so the predicate is
added there once. With the machine row invisible, rotation answers not found
and never rebuilds a machine credential as a personal token.

### Not in this change

Reserving machine principal names so they cannot equal a login. With the
routes separated, a shared name no longer grants anything.
