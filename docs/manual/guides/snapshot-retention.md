# Reclaiming snapshot storage

A marketplace polled daily accumulates a snapshot per upstream commit forever,
each pinning a commit in the quarantine repository. Retention reclaims that
space on stated criteria, with a restore window so a mistake is recoverable.

Nothing here happens on its own until you turn it on.

!!! warning "This guide enables a destructive capability"

    Retention deletes. Work through it in the order below — preview first,
    enable second — and read
    [Snapshot retention](../reference/retention.md) for exactly what the criteria
    select and what they can never touch.

## 1. See what a policy would delete

The dry run writes nothing and works with retention disabled, which is the whole
point: inspect the effect before switching the scheduler on.

```console
$ curl 'localhost:8080/api/retention/candidates'
```

```json
[{"snapshotId":17,"marketplace":"acme","sha":"3f9c2ab...","state":"held",
  "reason":"held-too-long","createdAt":"2026-04-02T08:11:03Z"},
 {"snapshotId":21,"marketplace":"acme","sha":"9d1e4c7...","state":"rejected",
  "reason":"superseded","createdAt":"2026-05-19T14:02:47Z"}]
```

`reason` is the criterion that selected it. Add `?marketplace=acme` to scope the
preview to one upstream.

If the list is longer than you expected, adjust the policy rather than the plan —
`held-max-age` and `superseded-min-age` are the two knobs that move it most.

## 2. Choose the policy

Global defaults with per-marketplace overrides, in your deployment
configuration:

```yaml
skills-gateway:
  retention:
    enabled: false          # still off; step 3 turns it on
    defaults:
      held-max-age: 90d
      superseded: true
      superseded-min-age: 30d
      min-idle: 30d
      restore-window: 14d
    marketplaces:
      acme:
        held-max-age: 30d   # a busy upstream; everything else inherited
```

Unset override fields fall back to `defaults`, so a per-marketplace block only
states what differs. The full reference is
[Configuration](../reference/configuration.md#retention).

Two rules worth internalising before you tune:

- **An approved snapshot is never eligible**, by policy or by hand. Retention
  cannot delete what the facade is serving.
- **`min-idle` is a veto, not a selector.** It only removes candidates that were
  fetched recently; it never selects anything.

## 3. Run a pass by hand

Still with the scheduler off, apply the policy once and watch what happens:

```console
$ curl -X POST 'localhost:8080/api/retention/evaluate?marketplace=acme'
```

```json
{"selected":2,"acted":2}
```

The selected snapshots are now **soft-deleted**: marked with a reason and a
`purge_after` deadline, their vetting state untouched, and restorable for the
whole restore window. Nothing has been removed from git.

Check the portal — [Marketplace detail](../reference/portal.md#marketplace-detail)
shows each deleted snapshot with a `deleted` badge and its restore deadline.

## 4. Restore anything you did not mean to delete

=== "Portal"

    On the marketplace detail page, the deleted snapshot's **Restore** button.
    It toasts *Snapshot {id} restored*.

=== "API"

    ```console
    $ curl -X POST localhost:8080/api/snapshots/17/restore
    ```

**409** means the snapshot is not deleted; **404** means compaction has already
removed it, and at that point there is nothing to restore.

You can also delete a single snapshot by hand — the **Delete** button, or
`DELETE /api/snapshots/17`, recorded with the reason `manual`. Approved
snapshots offer no delete control and the endpoint refuses them with **409**.

## 5. Enable the schedulers

Once the previews and a manual pass look right:

```yaml
skills-gateway:
  retention:
    enabled: true
    poll-interval: 1h         # evaluation: marks
    compaction-interval: 6h   # compaction: removes
    batch-size: 200
```

Evaluation soft-deletes on its interval; compaction permanently removes
snapshots whose restore window has elapsed, deleting the pinned quarantine ref
and garbage-collecting the repository so the objects are actually reclaimed.

!!! danger "Compaction is the irreversible half"

    Restore works only before `purge_after`. After compaction the row and the
    unreachable objects are gone; what remains is the ledger entry saying the
    SHA existed and was purged. Give yourself a restore window you would
    actually notice a mistake within — the default is 14 days.

To force a compaction pass — after a deliberate cleanup, say:

```console
$ curl -X POST localhost:8080/api/retention/compact
```

```json
{"selected":2,"acted":2}
```

`selected` is what was due; `acted` is what was actually removed. A snapshot
whose quarantine ref could not be deleted is left in place for the next pass
rather than removed from the table.

## 6. Watch it

Every retention action is in the append-only ledger with the acting identity:
`retention-evaluated:…`, `snapshot-soft-deleted:<reason>`, `snapshot-restored`
and `snapshot-purged`. Policy-driven deletions are attributed to
`retention-policy` rather than to a person.

Soft delete and restore also fire the `snapshot.soft_deleted` and
`snapshot.restored` webhook events, so an inventory system can follow deletions
the same way it follows approvals — see
[Receiving lifecycle webhooks](lifecycle-webhooks.md).

## Turning it off again

Set `enabled: false`. The schedulers stop; already-marked snapshots keep their
marks and stay restorable, and nothing new is selected or purged. Restoring them
is the explicit second step.
