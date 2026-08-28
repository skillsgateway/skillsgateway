# Estate

The declarative estate's API surface: the last reconciliation report and the
on-demand trigger. What the declaration itself looks like is in
[Configuration](../configuration.md#declarative-estate); the GitOps
walkthrough is in
[Declarative estate configuration](../../guides/declarative-estate.md).

Under role enforcement the report is **auditor-or-admin** (failure reasons
expose operator infrastructure, like the subscriber and sink listings) and the
trigger is **admin-only**.

**Machine reach.** `estate:read` covers the report and `estate:reconcile` the
trigger — separately, so a credential that reads drift cannot also cause a
convergence. See [Machine API credentials](tokens.md#machine-api-credentials).

---

## `GET /api/estate`

The most recent reconciliation run — startup or on-demand — with one entry per
declared object.

```console
$ curl localhost:8080/api/estate
```

```json
{"ranAt":"2026-08-18T06:00:00Z","trigger":"startup",
 "entries":[
   {"kind":"marketplace","name":"corp-marketplace","action":"created","detail":null},
   {"kind":"grant","name":"alice@example.com/approver/corp-marketplace","action":"unchanged","detail":null},
   {"kind":"webhook","name":"ci-bot","action":"updated","detail":"changed=secret"},
   {"kind":"audit-sink","name":"siem","action":"failed",
    "detail":"a declared secret must be at least 16 characters; supply it by environment-variable reference, never inline"}],
 "created":1,"updated":1,"unchanged":1,"failed":1}
```

| Field | Meaning |
| --- | --- |
| `trigger` | `startup` or `api`. |
| `entries[].kind` | `marketplace`, `grant`, `webhook`, or `audit-sink`. |
| `entries[].action` | `created`, `updated`, `unchanged`, or `failed`. |
| `entries[].detail` | What changed (`changed=url,secret`) or why the entry failed. Never a secret value. |

The report is held in memory; after a restart the startup run repopulates it.
A `failed` entry also has a ledger record (`estate-reconciliation-failed`) for
every run in which it fails — the report is the current picture, the ledger is
the history.

| Status | Meaning |
| --- | --- |
| 200 | The last run's report. |
| 403 | Enforcement is enabled and the caller holds neither auditor nor admin. |
| 404 | No reconciliation has run (not reachable in practice: startup always runs one). |

---

## `POST /api/estate/reconcile`

Run the same additive, idempotent reconciliation as startup against the
current declaration, and return its report. Takes no request body.

```console
$ curl -X POST localhost:8080/api/estate/reconcile
```

A converged estate reconciles with zero writes and zero ledger entries; the
trigger itself is always recorded on the ledger as
`estate-reconcile-triggered` with the acting identity. Applied changes are
recorded under the same event names as their API equivalents
(`marketplace-registered`, `sync-mode-changed`, `role-granted`,
`webhook-subscriber-created`/`-updated`, `audit-sink-created`/`-updated`) with
the actor `config-reconciler`.

| Status | Meaning |
| --- | --- |
| 200 | The run's report, converged or not — a failed entry is a report line, not an error status. |
| 403 | Enforcement is enabled and the caller is not an admin. |

!!! note "Reconciliation never deletes"

    There is no prune mode. An object absent from the declaration — removed
    from it, or never in it — is untouched, whatever this endpoint is asked.
    Deregistration stays an explicit API/portal action.
