# Audit

The ledger is an append-only table. No code path in the product issues an
`UPDATE` or `DELETE` against it — see
[Snapshots and the audit ledger](../../concepts/snapshots-and-ledger.md) for the
conceptual treatment.

---

## `GET /api/audit`

Return the ledger. Requires an authenticated session; any authenticated session
may read it.

```console
$ curl localhost:8080/api/audit
```

```json
[{"id":1,"ts":"2026-08-15T09:04:11Z","source":"10.0.0.4","principal":"alice@example.com",
  "marketplace":"acme","event":"info-refs","ref":"refs/heads/main","sha":"3f9c2ab..."},
 {"id":2,"ts":"2026-08-15T09:04:11Z","source":"10.0.0.4","principal":"alice@example.com",
  "marketplace":"acme","event":"upload-pack","ref":null,"sha":"3f9c2ab..."}]
```

**200.** Rows are returned untyped, which is why the portal renders this table
schema-lessly.

!!! note "No filtering, search or paging"

    This endpoint returns the whole table. It is a recent-activity view, not an
    investigation tool. For a continuous, resumable feed use the export
    endpoints below.

---

## Export endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /api/audit/export` | Stream entries as `application/x-ndjson`, one per line in ledger order. `?after=` (default `0`) and `?limit=` (default `1000`, capped at `10000`). The resume sequence comes back in `X-Skills-Gateway-Audit-Cursor`. |
| `POST /api/audit/sinks` | Register a push sink. **201** with the show-once signing secret; **400** disallowed scheme, **409** name taken, **422** bad name. |
| `GET /api/audit/sinks` | List sinks with `cursorPosition`, `ledgerHead` and `behind`. Secrets are never returned. |
| `PUT /api/audit/sinks/{id}/cursor` | Set the position — replay. **200** with the sink, **404** unknown. |
| `DELETE /api/audit/sinks/{id}` | Remove the sink and its delivery channel. **204**, or **404**. |

Both paths withhold entries younger than the commit-settling lag, and every
entry carries its ledger `id` as the de-duplication key. Task-shaped coverage —
polling, batch payload, signature, replay — is in
[Exporting the audit ledger](../../guides/exporting-the-audit-ledger.md).

## Entry fields

| Field | Meaning |
| --- | --- |
| `id` | `BIGSERIAL`. The ordering key. |
| `ts` | When the entry was appended. |
| `source` | Client address for a facade fetch; the literal `admin` for an administrative action. |
| `principal` | The PAT principal for a fetch, the OIDC principal for an admin action. |
| `marketplace` | The marketplace name, or `-` when not marketplace-scoped. |
| `event` | What happened — see below. |
| `ref` | The ref involved, when there is one. |
| `sha` | The commit involved, when there is one. |
| `detail` | Free-text qualifier, when the entry needs one: the vetting chain outcome, a connector's verdict, or the reason a reviewer gave when overriding a blocked outcome. |

## Events

**From the facade**

| Event | When |
| --- | --- |
| `info-refs` | A client asked what refs exist. Carries the resolved `main` SHA. |
| `upload-pack` | One entry per wanted object when the packfile is served. |

Negotiation rounds are not recorded.

**From the API** — registration, ingestion, approve and reject, each carrying
the acting OIDC principal.

**From the vetting chain** — every run, recorded under the `admin` source with
`vetting` as the principal:

| Event | When | `detail` |
| --- | --- | --- |
| `vetting-verdict` | One per connector per run. | `{connector}={state}`, e.g. `secret-scan=fail`. |
| `vetting-completed` | Once per run. | `trigger={ingestion\|revet-scheduled\|revet-manual}; outcome={clear\|blocked}; connectors={n}; chain={connector@version,…}`. |

**From continuous re-vetting** — see
[Re-vetting approved content](../../guides/re-vetting.md). The scheduled sweep
records `revet-policy` as the principal; an on-demand run records the operator.

| Event | When | `detail` |
| --- | --- | --- |
| `revet-clear` | A re-vetting run found nothing. | `trigger=…; outcome=…`. |
| `revet-inconclusive` | The chain could not conclude, so the snapshot stays approved. | The connectors that could not answer. |
| `revet-violation` | A retroactive violation on an approved snapshot. | `trigger=…; mode={WARN\|ENFORCE}; connectors=…; rules=…; fetchedBy={n}`. |
| `revet-violation-affected` | One per identity that had already fetched the snapshot. | `principal=…; fetches=…; lastFetch=…`. |
| `snapshot-revoked` | The state transition out of `approved`. | The violation that caused it. |
| `snapshot-unpublished` | The published refs were removed. | Whether the marketplace still serves anything. |
| `snapshot-unpublish-failed` | A revoked snapshot's refs could not be removed. | The failure. **Needs a person**: the record and the wire disagree. |

## What this answers

Because entries carry principal, marketplace and SHA, the inventory question is
a single query:

```sql
SELECT DISTINCT principal
FROM fetch_log
WHERE sha = '3f9c2ab...' AND ts > now() - interval '90 days';
```

`GET /api/snapshots/{id}/fetchers` answers exactly this from the API, and it is
what the portal shows beneath a revoked snapshot.

That is the "which of our developers received this exact content" question that
git distribution otherwise cannot answer.
