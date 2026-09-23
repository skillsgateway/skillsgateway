# Audit

The product only ever appends to the ledger: no code path issues an `UPDATE` or
`DELETE` against it. That is the code's discipline and not a constraint the
schema enforces — see
[Snapshots and the audit ledger](../../concepts/snapshots-and-ledger.md) for the
conceptual treatment and for what does provide tamper-evidence.

---

The ledger and export reads and the sink listing require **auditor** (or
admin); sink create, delete, and cursor reset require **admin**. See
[Delegated administration](../../guides/delegated-administration.md).

**Machine reach.** `audit:read` covers the ledger read and the export;
`audit-sinks:read` the sink listing; `audit-sinks:write` sink creation,
deletion and cursor reset. A machine read of the ledger writes **no** ledger
entry, deliberately: logging it would make a polling exporter append an entry
that is itself new content to export. See
[Machine API credentials](tokens.md#machine-api-credentials).

---

## `GET /api/v1/audit`

One page of the ledger, newest entry first. Requires an authenticated session with
the auditor role.

```console
$ curl localhost:8080/api/v1/audit
$ curl 'localhost:8080/api/v1/audit?before=41&limit=100'
```

```json
{"entries":[
  {"id":43,"ts":"2026-08-15T11:22:07Z","source":"10.0.0.9","principal":"team-payments",
   "marketplace":"acme","event":"upload-pack","ref":"refs/snapshots/9d01c44...","sha":"9d01c44..."},
  {"id":42,"ts":"2026-08-15T09:04:11Z","source":"10.0.0.4","principal":"alice@example.com",
   "marketplace":"acme","event":"upload-pack","ref":"refs/heads/main","sha":"3f9c2ab..."}],
 "nextBefore":42}
```

**200.** `before` (default `0`, meaning start at the newest entry) and `limit`
(clamped to the same bounds as the export). Page backwards by passing the previous
response's `nextBefore` as `before`; it is absent once the page reaches the oldest
entry the ledger still holds.

!!! note "Newest first, the opposite of the export"

    Deliberate. An export consumer resumes *forward* from where it stopped; a person
    opening the audit page wants what happened most recently. Same rows and the same
    typed shape — opposite ends of the ledger.

    Paged by ledger sequence rather than by offset, so a page stays stable while the
    ledger is being appended to underneath a reader. An offset page over an
    append-only table cannot promise that.

---

## Export endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/audit/export` | Stream entries as `application/x-ndjson`, one per line in ledger order. `?after=` (default `0`) and `?limit=` (default `1000`, capped at `10000`). The resume sequence comes back in `X-Skills-Gateway-Audit-Cursor`. |
| `POST /api/v1/audit/sinks` | Register a push sink. **201** with the show-once signing secret; **400** disallowed scheme, **409** name taken, **422** bad name. |
| `GET /api/v1/audit/sinks` | List sinks with `cursorPosition`, `ledgerHead` and `behind`. Secrets are never returned. |
| `PUT /api/v1/audit/sinks/{id}/cursor` | Set the position — replay. **200** with the sink, **404** unknown. |
| `DELETE /api/v1/audit/sinks/{id}` | Remove the sink and its delivery channel. **204**, or **404**. |

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
| `actorType` / `actor_type` | What kind of actor acted: `human`, `machine` or `system` (GW_AUDIT_0007). See below. |
| `marketplace` | The marketplace name, or `-` when not marketplace-scoped. |
| `marketplaceId` / `marketplace_id` | Id of the marketplace the entry concerns, or null when it concerns none (GW_AUDIT_0009). A [removed](marketplaces.md#delete-marketplacesname) marketplace's name can be registered again, so the name alone does not say which marketplace an entry meant; the id does. Not a foreign key: the removed marketplace's record is kept, but the ledger does not depend on it. On `GET /api/v1/audit` and in the export. |
| `event` | What happened — see below. |
| `ref` | The ref involved, when there is one. For a facade fetch this is a ref the facade [advertised](../git-facade.md#what-is-served) for that request — see [Events](#events). |
| `sha` | The commit involved, when there is one. |
| `detail` | Free-text qualifier, when the entry needs one: the vetting chain outcome, a vetter's verdict, or the reason a reviewer gave when overriding a blocked outcome. |
| `tokenId` / `token_id` | Id of the credential that authenticated a facade entry (GW_AUTH_0009) or a machine API entry (GW_AUDIT_0007); null on interactive admin entries and on entries older than per-credential attribution. `GET /api/v1/tokens` gives the owner the id→name mapping. |
| `credential_kind` | What kind of credential authenticated a facade fetch (GW_AUTH_0041): `pat` for a gateway-issued access token, `idp` for an [identity-provider bearer token](../git-facade.md#identity-provider-bearer-tokens). Null on every entry no credential authenticated — administrative and system entries — and on facade entries written before this column existed. Available on `GET /api/v1/audit`; the export payload is unchanged, exactly as it is for `actor_type`. |

### The actor type

| Value | `principal` is | Produced by |
| --- | --- | --- |
| `human` | the identity-provider subject, or the PAT principal | an interactive session, and every facade fetch by a credential holding no API scope |
| `machine` | the credential's own principal | a [machine API credential](tokens.md#machine-api-credentials), and a facade fetch by one |
| `system` | `config-reconciler`, `scheduler`, `webhook`, `revet-policy`, `catalog-builder`, `publication-reconciler` or `system` | the gateway acting on its own |

It is **denormalised on purpose**, and never a join: an entry written years ago
must still say what it meant after the credential it names has been revoked and
its row deleted — the same reasoning `token_id` and `credential_kind` already
carry. Query it
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
| `info-refs` | A client asked what refs exist. `ref` is `refs/heads/main` and `sha` its resolved SHA — an advertisement genuinely is about the tip. |
| `upload-pack` | One entry per wanted object when the packfile is served. `sha` is the object; `ref` is the advertised ref that object resolves to. |

Negotiation rounds are not recorded.

!!! note "Which ref an `upload-pack` entry names"

    A want that is not the served tip can only have come from a
    `refs/snapshots/<sha>` advertisement, so it records that ref — this is how a
    fetch of a snapshot a later approval has superseded is distinguishable from a
    clone of the current tip.

    A want **equal** to the tip records `refs/heads/main`, even if the client
    named the snapshot ref. While a snapshot is current the two refs are the same
    commit, and the smart protocol carries only object ids in a want, so the two
    requests are not separable; the tip is the recorded answer, deterministically.
    Nothing is lost evidentially — `sha` pins the delivered content exactly.

    `ref` is `null` if a want resolves to no advertised ref. Under the facade's
    request policy every want is an advertised tip, so this does not arise in
    practice; the column says it does not know rather than naming a ref the
    client did not ask for.

    Entries written before this behaviour shipped record `refs/heads/main` for
    every `upload-pack` want, including snapshot-ref fetches. The ledger is
    append-only and is not rewritten.

**From the API** — registration, ingestion, approve and reject, each carrying
the acting OIDC principal.

| Event | When | `detail` |
| --- | --- | --- |
| `roles-read` | Every **authorized** read of `GET /api/v1/roles`, by a person or a machine alike (GW_AUDIT_0007). A refused read records nothing. | `grants={n}`. |
| `machine-credential-created` | A machine API credential was provisioned. The actor is the administrator who provisioned it; the credential's own actions are recorded under its own principal. | `credential {id} '{name}' scopes=…; expires=…`. |
| `machine-credential-rotated` | A machine API credential got a new secret with an identical grant. | As above. |
| `machine-credential-revoked` | A machine API credential was revoked. | `credential {id} '{name}' principal=…`. |
| `marketplace-removed` | A marketplace was [removed](marketplaces.md#delete-marketplacesname). Its withdrawals follow as `snapshot-revoked` and `snapshot-unpublished`. | `withdrew {n} approved snapshot(s); reason: …`. |
| `marketplace-push-scopes-removed` | The same removal took the name out of tokens' publication grants. Written only when a token held one. | `tokens=[{id}, …]`. |

Reads of the ledger itself record **nothing**, deliberately: an exporter polling
on a cursor loop would otherwise append one entry per poll, and that entry is
itself new content to export.

**From the catalog builder** — recorded with `catalog-builder` as the principal
and the catalog's own name as the marketplace, because a contested name is the
gateway's finding rather than an act of whoever approved the snapshot that
triggered the rebuild. See
[Contested names](../../guides/virtual-catalog.md#contested-names).

| Event | When | `detail` |
| --- | --- | --- |
| `catalog-name-collision` | One per name a rebuild withheld because more than one plugin in the served estate claimed it. The name is published for no claimant. | `{name} claimed by {marketplace}, {marketplace}`. |

**From the vetting chain** — every run, recorded under the `admin` source with
`vetting` as the principal:

| Event | When | `detail` |
| --- | --- | --- |
| `vetting-verdict` | One per vetter per run. | `{vetter}={state}`, e.g. `secret-scan=fail`. |
| `vetting-completed` | Once per run. | `trigger={ingestion\|revet-scheduled\|revet-manual}; outcome={clear\|blocked}; vetters={n}; chain={vetter@version,…}`. |

**From continuous re-vetting** — see
[Re-vetting approved content](../../guides/re-vetting.md). The scheduled sweep
records `revet-policy` as the principal; an on-demand run records the operator.

| Event | When | `detail` |
| --- | --- | --- |
| `revet-clear` | A re-vetting run found nothing. | `trigger=…; outcome=…`. |
| `revet-inconclusive` | The chain could not conclude, so the snapshot stays approved. | The vetters that could not answer. |
| `revet-violation` | A retroactive violation on an approved snapshot. | `trigger=…; mode={WARN\|ENFORCE}; vetters=…; rules=…; fetchedBy={n}`. |
| `revet-violation-affected` | One per identity that had already fetched the snapshot. | `principal=…; fetches=…; lastFetch=…`. |
| `snapshot-revoked` | The state transition out of `approved`. | The violation that caused it. |
| `snapshot-unpublished` | The published refs were removed. | Whether the marketplace still serves anything. |
| `snapshot-unpublish-failed` | A revoked snapshot's refs could not be removed. | The failure. **Needs a person**: the record and the wire disagree. |

**From publication reconciliation** — at startup, before the web surface serves
its first request, recorded with `publication-reconciler` as the principal and
the affected marketplace as the marketplace. See
[When the repair fails too](../../guides/approving-snapshots.md#when-the-repair-fails-too).

| Event | When | `detail` |
| --- | --- | --- |
| `publication-repaired` | A snapshot the database records as approved was not being served, and was republished. | The snapshot's SHA. |
| `publication-served-not-approved` | A ref is served for a snapshot the database does not record as approved. Nothing is retracted. **Needs a person**: the record and the wire disagree. | The SHA being served. |

## What this answers

Because entries carry principal, marketplace and SHA, the inventory question is
a single query:

```sql
SELECT DISTINCT principal
FROM fetch_log
WHERE sha = '3f9c2ab...' AND ts > now() - interval '90 days';
```

`GET /api/v1/snapshots/{id}/fetchers` answers exactly this from the API, and it is
what the portal shows beneath a revoked snapshot. That endpoint is the approver's
view of one snapshot — an admin, or an approver of its marketplace; an auditor
asks the same question of the whole ledger through the reads on this page.

That is the "which of our developers received this exact content" question that
git distribution otherwise cannot answer.
