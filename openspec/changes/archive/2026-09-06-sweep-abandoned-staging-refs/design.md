# Design: sweep-abandoned-staging-refs

## Context

`GitStorage.publish` copies a snapshot's objects from quarantine into the
published repository under `refs/staging/<sha>`, then calls
`commitPublication`, which moves `refs/heads/main` and writes
`refs/snapshots/<sha>` as one transition and drops the staging reference. The
split is what makes publication all-or-nothing: object transfer is the slow,
identical-everywhere half, and the transition is the half a backend can make
atomic.

Everything that *finishes* cleans up after itself. What does not is a process
that dies between the two halves. Nothing revisits it: `ApprovalService.repair`
puts the snapshot row back, so the row is consistent, and the leftover
reference is invisible to every reader — the facade does not advertise it and no
query looks for it.

Three constraints shape the fix:

- **The storage seam has no database.** `GitStorage` is deliberately ignorant of
  snapshots, marketplaces and rows; that ignorance is what keeps "quarantine is
  never served" a property of call sites rather than of a parameter. Deciding
  whether a staged commit is still a snapshot is a database question.
- **Retention already owns this shape.** `compact` deletes a pinned reference
  with JGit and then garbage-collects once per marketplace per pass, with the
  expiry set to now and `setPrunePreserved(true)`. The sweep is the same
  operation pointed at the other repository role.
- **There are two backends and they must not differ.** A filesystem
  `RefDirectory` and a manifest in an object store agree on `getRefsByPrefix`
  and on `RefUpdate`, and on nothing outside them.

## Goals / Non-Goals

**Goals**

- An abandoned staging reference is removed and its objects become collectable.
- A publication in flight is never touched, on either backend.
- The facade's namespaces — `refs/heads/main` and `refs/snapshots/*` — are
  unreachable from the sweep by construction.

**Non-Goals**

- Not a reconciliation of the published estate against the database. That is a
  larger, riskier idea (it would have opinions about served references); this
  removes one unadvertised reference kind and nothing else.
- Not a change to how publication works. The staging namespace, its name and its
  lifecycle are exactly as they were.

## Decisions

1. **The sweep is a second half of the compaction pass, not a third schedule.**
   `compact` already is "the pass that reclaims storage", it already runs on
   `compaction-interval`, and it is already reachable on demand through
   `POST /api/retention/compact` — which is what lets an operator run the sweep
   without enabling the scheduler. Its failures are caught separately so a sweep
   that cannot read the published side does not turn a completed purge into a
   failed pass. `PassResult` keeps counting snapshots; what the sweep did is on
   the ledger as `staging-refs-swept:count=<n>`.

2. **Two conditions, and the second one is the change.** A reference is removed
   only when

   - no live snapshot row of that marketplace names the commit it points at, and
   - it has been under observation for longer than `staging-ref-max-age`.

   The first is necessary but not sufficient. `ApprovalService` commits the
   approved row *before* it publishes, so an in-flight publication always has a
   row — but the sweep's reference listing and its database query are two reads
   taken at different instants, and a publication that starts between them
   presents a staged reference the query did not see. Without the bound the
   sweep would delete that reference and collect its objects while
   `commitPublication` was about to point the served tip at them: a marketplace
   published at a commit whose content is gone. A disk leak turned into data
   loss is not an improvement.

   The reads are *also* ordered — references first, rows afterwards — which
   closes today's window on its own, because a reference staged before the
   listing has necessarily committed its row before the query. That ordering is
   a property of the current publication path; the age bound is what keeps the
   guarantee when the path changes.

3. **The clock is a table, because git has no portable one.** A reference has no
   creation time. `RefDirectory` has a file mtime and the object-store manifest
   has neither; a reflog is not written for these updates and would not exist on
   a DFS backend anyway. Asking the seam for one would mean a per-backend answer
   to a question only retention asks.

   So `staging_ref_sightings` holds `(marketplace, ref, first_seen_at)`. Each
   pass records `now` for references it has not seen, forgets rows for
   references that are gone, and reads back the stamps. The age it yields is an
   **under-estimate** of the reference's real age — a reference created just
   before the first pass gets that pass's timestamp — and that is the only safe
   direction: under-estimating delays a sweep, over-estimating walks it into a
   running publication. Forgetting on disappearance is what stops a re-staged
   reference of the same name inheriting the previous one's age.

   *Alternative rejected: in-memory sightings.* Restarts would reset the clock,
   and a gateway restarted daily would never sweep — the leak would survive its
   own fix on exactly the deployments most likely to have produced it.

   *Alternative rejected: the audit ledger as the clock.* A crashed publication
   writes no ledger entry (the entry is written after `approve` returns), so the
   newest entry naming the SHA predates the staging reference. That
   over-estimates the age, which is the unsafe direction.

4. **The marketplace is named, not referenced.** The sweep enumerates published
   repositories through `storage.marketplaces(Role.PUBLISHED)` rather than
   iterating marketplace rows, because `storage.published(name)` *creates* a
   repository that is not there — iterating rows would materialise an empty
   published repository for every marketplace that has never had one. A
   consequence is that a repository whose marketplace row has gone is still
   swept, which is why `staging_ref_sightings.marketplace` is a name with no
   foreign key.

5. **The commit comes from the reference's target, not from its name.**
   `refs/staging/<sha>` is written with the two in agreement, but it is the
   target that pins objects, and a name is not evidence about what a reference
   points at.

6. **A property, not an estate object.** The project's rule is that new
   API-managed runtime state extends `skills-gateway.estate.*`. This is not
   that: `staging-ref-max-age` describes how long a publication can take on
   *this deployment's* storage — a fact about hardware and snapshot sizes, in
   the same family as `poll-interval` and `batch-size`, which are also
   properties. There is no object to name, nothing to grant, and nothing an API
   caller would create. Like every other `skills-gateway.retention.*` knob it is
   operator tuning, and it changes only how patient the gateway is, never what
   it may reach.

7. **The default is 24 hours.** The two failure modes are not symmetric. Being
   too generous costs the disk of an abandoned snapshot for another day —
   exactly the cost that already exists today, forever. Being too tight risks
   collecting a live publication's objects. A day is far longer than any
   plausible object transfer, including a first publication of a large
   marketplace over an object store on a bad link, and short enough that the
   leak is measured in days rather than in the estate's lifetime.

## Risks / Trade-offs

- **A pass deletes a reference a publication is still using.** Only if that
  publication has been running for longer than `staging-ref-max-age` *and* its
  snapshot row has gone in the meantime, which requires the row to have been
  soft-deleted and compacted while the publication ran. Both conditions are
  needed; either alone leaves the reference in place.
- **One extra table.** It holds at most one row per staging reference — which,
  on a healthy gateway, is zero — and is cleaned by the same pass that reads it.
- **Garbage collection now runs on published repositories.** Only when the sweep
  actually removed something, so a healthy gateway never reaches it, and with
  the same settings compaction already uses on quarantine. Objects still
  reachable from `refs/heads/main` or `refs/snapshots/*` are untouched, which is
  what keeps served content served.
