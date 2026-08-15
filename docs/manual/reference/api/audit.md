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
    investigation tool. Continuous export to an external system is a separate
    capability.

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

## Events

**From the facade**

| Event | When |
| --- | --- |
| `info-refs` | A client asked what refs exist. Carries the resolved `main` SHA. |
| `upload-pack` | One entry per wanted object when the packfile is served. |

Negotiation rounds are not recorded.

**From the API** — registration, ingestion, approve and reject, each carrying
the acting OIDC principal.

## What this answers

Because entries carry principal, marketplace and SHA, the inventory question is
a single query:

```sql
SELECT DISTINCT principal
FROM fetch_log
WHERE sha = '3f9c2ab...' AND ts > now() - interval '90 days';
```

That is the "which of our developers received this exact content" question that
git distribution otherwise cannot answer.
