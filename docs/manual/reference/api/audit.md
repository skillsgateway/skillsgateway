# Audit

The ledger is an append-only table. No code path in the product issues an
`UPDATE` or `DELETE` against it — see
[Snapshots and the audit ledger](../../concepts/snapshots-and-ledger.md) for the
conceptual treatment.

---

With [role enforcement](../../guides/delegated-administration.md) enabled: the
ledger and export reads and the sink listing require **auditor** (or admin);
sink create, delete, and cursor reset require **admin**.

**Machine reach.** `audit:read` covers the ledger read and the export;
`audit-sinks:read` the sink listing; `audit-sinks:write` sink creation,
deletion and cursor reset. A machine read of the ledger writes **no** ledger
entry, deliberately: logging it would make a polling exporter append an entry
that is itself new content to export. See
[Machine API credentials](tokens.md#machine-api-credentials).

---

## `GET /api/audit`

Return the ledger. Requires an authenticated session — and, with role
enforcement enabled, the auditor role.

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
| `principal` | The PAT principal for a fetch, the OIDC principal for an admin action, the credential's own principal for a machine API action. |
| `actorType` / `actor_type` | What kind of actor acted: `human`, `machine` or `system` (GW_0128). See below. |
| `marketplace` | The marketplace name, or `-` when not marketplace-scoped. |
| `event` | What happened — see below. |
| `ref` | The ref involved, when there is one. |
| `sha` | The commit involved, when there is one. |
| `detail` | Free-text qualifier, when the entry needs one: the vetting chain outcome, a connector's verdict, or the reason a reviewer gave when overriding a blocked outcome. |
| `tokenId` / `token_id` | Id of the credential that authenticated a facade entry (GW_0067) or a machine API entry (GW_0128); null on interactive admin entries and on entries older than per-credential attribution. `GET /api/tokens` gives the owner the id→name mapping. |

### The actor type

| Value | `principal` is | Produced by |
| --- | --- | --- |
| `human` | the identity-provider subject, or the PAT principal | an interactive session, and every facade fetch by a credential holding no API scope |
| `machine` | the credential's own principal | a [machine API credential](tokens.md#machine-api-credentials), and a facade fetch by one |
| `system` | `config-reconciler`, `scheduler`, `webhook`, `revet-policy` or `system` | the gateway acting on its own |

It is **denormalised on purpose**, and never a join: an entry written years ago
must still say what it meant after the credential it names has been revoked and
its row deleted — the same reasoning `token_id` already carries. Query it
directly (`WHERE actor_type = 'machine'`) rather than comparing `principal`
against a list of names.

!!! note "Facade fetches are typed by the credential, imperfectly and knowingly"

    A facade fetch records `machine` when the credential that authenticated it
    holds any API scope, and `human` otherwise. A fetch-only PAT sitting in a CI
    variable therefore still records as `human`. That is the only distinction
    the data supports, and it is truthful about what this column introduces
    rather than guessing about what it does not. Existing entries are `human`,
    which is what they have always implicitly claimed.

## Events

**From the facade**

| Event | When |
| --- | --- |
| `info-refs` | A client asked what refs exist. Carries the resolved `main` SHA. |
| `upload-pack` | One entry per wanted object when the packfile is served. |

Negotiation rounds are not recorded.

**From the API** — registration, ingestion, approve and reject, each carrying
the acting OIDC principal.

| Event | When | `detail` |
| --- | --- | --- |
| `roles-read` | Every **authorized** read of `GET /api/roles`, by a person or a machine alike (GW_0128). A refused read records nothing. | `grants={n}`. |
| `machine-credential-created` | A machine API credential was provisioned. The actor is the administrator who provisioned it; the credential's own actions are recorded under its own principal. | `credential {id} '{name}' scopes=…; expires=…`. |
| `machine-credential-rotated` | A machine API credential got a new secret with an identical grant. | As above. |
| `machine-credential-revoked` | A machine API credential was revoked. | `credential {id} '{name}' principal=…`. |

Reads of the ledger itself record **nothing**, deliberately: an exporter polling
on a cursor loop would otherwise append one entry per poll, and that entry is
itself new content to export.

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
