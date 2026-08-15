# Marketplaces and snapshots

The core API: registration, ingestion, the approval gate and provenance. All
paths are relative to `/api`.

## Representations

**Marketplace**

```json
{"id":1,"name":"acme","url":"https://github.com/acme/skills.git",
 "createdAt":"2026-08-15T09:00:00Z","forge":"github","forgeProject":"acme/skills",
 "description":"Acme internal skills","upstreamUpdatedAt":"2026-08-14T18:20:00Z",
 "snapshots":[]}
```

**Snapshot**

```json
{"id":42,"marketplaceId":1,"sha":"3f9c2ab...","state":"held",
 "violation":null,"createdAt":"2026-08-15T09:01:00Z",
 "decidedBy":null,"decidedAt":null}
```

`state` is one of `held`, `approved`, `rejected`.

---

## `POST /marketplaces`

Register an upstream repository. Fetches nothing.

**Body** — `{name, url, ref?}`

```console
$ curl -X POST localhost:8080/api/marketplaces \
    -H 'Content-Type: application/json' \
    -d '{"name":"acme","url":"https://github.com/acme/skills.git"}'
```

| Status | Cause |
| --- | --- |
| 201 | Registered; returns the marketplace. |
| 400 | URL scheme not allowlisted, or `ref` is present and not `main`. |
| 409 | Name already exists. |
| 422 | Name fails `^[a-z0-9][a-z0-9_-]*$`. |

Both 400 cases are trust-boundary rejections — see
[Compatibility and allowlists](../compatibility.md).

---

## `GET /marketplaces`

All marketplaces, each with its forge metadata and full snapshot list. This is
the portal's primary query; there is no per-marketplace endpoint.

**200** — array of marketplaces.

---

## `POST /marketplaces/{name}/ingest`

Clone the upstream default branch into quarantine and pin the tip commit as
`refs/snapshots/{sha}`. Creates a snapshot in state `held`.

```console
$ curl -X POST localhost:8080/api/marketplaces/acme/ingest
```

| Status | Cause |
| --- | --- |
| 201 | Snapshot captured; returns it. |
| 404 | Unknown marketplace. |
| 502 | Ingestion failed — upstream unreachable, or the manifest was rejected. |

Ingesting a commit already captured does not create a second snapshot.

---

## `GET /snapshots/{id}/content`

What the snapshot declares — the review surface. Works on `held` snapshots,
because reviewing must not require serving.

```json
{"snapshotId":42,"sha":"3f9c2ab...","state":"held",
 "plugins":[{"name":"acme-tools","description":"Deployment helpers",
             "source":"./plugins/acme-tools",
             "skills":[{"name":"deploy","path":"skills/deploy/SKILL.md"}]}]}
```

**200** · **404** unknown snapshot.

---

## `GET /snapshots/{id}/vetting`

The snapshot's latest vetting chain run: each connector's verdict in chain
order, the findings behind it, the waivers currently suppressing any of them,
and the fail-closed **effective** aggregate that gates approval. A snapshot the
chain has never run against reports `"outcome":"BLOCKED"` and `"run":null`.

```json
{"snapshotId":12,"outcome":"CLEAR_WITH_WAIVERS","recordedOutcome":"BLOCKED",
 "run":{"runId":5,"snapshotId":12,"trigger":"ingestion","outcome":"BLOCKED",
        "startedAt":"...","finishedAt":"...",
        "verdicts":[
          {"verdictId":9,"connector":"secret-scan","position":0,"state":"FAIL",
           "detail":"1 finding(s); worst critical","reportUrl":null,
           "findings":[{"id":"aws-access-key-id","severity":"CRITICAL",
                        "location":"plugins/hello/DEPLOY.md:5",
                        "message":"an AWS access key id is committed in this file"}]},
          {"verdictId":10,"connector":"prompt-injection","position":1,
           "state":"PASS","detail":null,"reportUrl":null,"findings":[]}]},
 "suppressed":[{"connector":"secret-scan","ruleId":"aws-access-key-id",
                "location":"plugins/hello/DEPLOY.md:5","waiverId":3,
                "approvedBy":"alice","expiresAt":"2026-09-30T23:59:59Z"}],
 "uncovered":[],
 "waivers":[{"id":3,"marketplace":"corp-marketplace","ruleId":"aws-access-key-id",
             "scope":"SNAPSHOT","scopeValue":"a1b2c3…","justification":"documented dummy key",
             "approvedBy":"alice","createdAt":"...","expiresAt":"2026-09-30T23:59:59Z",
             "revokedAt":null,"revokedBy":null,"active":true}],
 "connectors":[{"name":"secret-scan","order":100,"description":"..."},
               {"name":"prompt-injection","order":200,"description":"..."}]}
```

| Field | Meaning |
| --- | --- |
| `outcome` | The **effective** outcome — the one that gates approval: `CLEAR`, `CLEAR_WITH_WAIVERS`, or `BLOCKED`. Recomputed on every request from the run and the waivers active at that instant. |
| `recordedOutcome` | What the connectors themselves concluded: `CLEAR` or `BLOCKED`. Never rewritten by a waiver. |
| `suppressed` | The findings an active waiver is currently removing from the computation. |
| `uncovered` | The blocking findings no active waiver covers — the waivers approval still needs. |
| `waivers` | The marketplace's waivers whose rule appears in this run, active and lapsed alike. |

`state` is one of `PASS`, `WARN`, `FAIL`, `ERROR`, `PENDING`; `severity` is one
of `INFO`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. What each means is described in
[Vetting — the connector chain](../../concepts/vetting.md).

| Status | Cause |
| --- | --- |
| 200 | The latest chain run, its waivers, and the configured chain. |
| 404 | Unknown snapshot. |

---

## Vetting waivers

A waiver accepts one finding rule, on one marketplace, within one scope, until
one expiry. See
[Waiving a vetting finding](../../guides/waiving-findings.md) for the task and
[the concept page](../../concepts/vetting.md#waivers-accepted-risks-with-a-scope-and-an-expiry)
for the matching rules.

### `POST /snapshots/{id}/waivers`

```json
{"ruleId":"aws-access-key-id","scope":"SNAPSHOT",
 "justification":"documented dummy key in the fixtures directory",
 "expiresAt":"2026-09-30T23:59:59Z"}
```

| Field | Required | Notes |
| --- | --- | --- |
| `ruleId` | yes | The finding's stable rule id. |
| `scope` | yes | `SNAPSHOT` or `PATH`. |
| `path` | for `PATH` | Repository-relative; must not contain `..`. Ignored for `SNAPSHOT`. |
| `justification` | yes | Free text; blank is refused. |
| `expiresAt` | yes | Must be in the future. There is no unlimited waiver. |

The marketplace — and, for `SNAPSHOT` scope, the commit SHA — are taken from the
snapshot, so a waiver cannot be scoped to content it does not belong to. The
approver is the acting session.

| Status | Cause |
| --- | --- |
| 201 | Waiver recorded; returns it with `active`. |
| 400 | Missing justification or expiry, an expiry in the past, or an unusable scope. |
| 404 | Unknown snapshot. |

### `GET /marketplaces/{name}/waivers`

Every waiver of the marketplace, newest first, active and lapsed alike. A lapsed
or revoked waiver is returned with `"active":false` — the record of what was
once accepted is part of the audit trail.

| Status | Cause |
| --- | --- |
| 200 | The marketplace's waivers. |
| 404 | Unknown marketplace. |

### `DELETE /waivers/{id}`

Revokes the waiver. It stops suppressing its finding on the next read, so a
snapshot cleared only by it becomes blocked again immediately. The row is kept,
with its revoker and time.

| Status | Cause |
| --- | --- |
| 200 | Revoked; returns the waiver with `active:false`. |
| 404 | Unknown waiver, or already revoked. |

---

## `POST /snapshots/{id}/approve`

**The only endpoint that publishes.** Fetches the pinned quarantine ref into the
published repository and force-updates `refs/heads/main` to that SHA.

**Takes no request body.** A snapshot whose effective vetting outcome is blocked
is refused, and the problem document carries both `blockingConnectors` and
`uncoveredFindings`:

```json
{"status":409,"title":"Vetting chain blocked this snapshot",
 "detail":"snapshot 12 cannot be approved: …",
 "blockingConnectors":["secret-scan"],
 "uncoveredFindings":[{"connector":"secret-scan","ruleId":"aws-access-key-id",
                       "location":"plugins/hello/DEPLOY.md:5","severity":"CRITICAL",
                       "message":"an AWS access key id is committed in this file"}]}
```

`uncoveredFindings` is the complete worklist: record a waiver for each entry and
the approval succeeds. Every waiver that was in force is appended to the audit
ledger as `waiver-applied`.

| Status | Cause |
| --- | --- |
| 200 | Approved; returns the snapshot with `decidedBy` and `decidedAt`. |
| 404 | Unknown snapshot. |
| 409 | The snapshot is not `held`, or its effective vetting outcome is blocked. |

!!! warning "A blocked snapshot can still be published"

    The gate is a set of written, attributed, expiring acceptances — not a
    prohibition. A fully waived snapshot publishes exactly as an ordinary
    approval does. The difference is that the ledger says which risk was
    accepted, by whom, and until when.

---

## `POST /snapshots/{id}/reject`

Mark the snapshot `rejected`. No repository is touched; whatever was already
approved keeps serving.

Same status codes as approve.

!!! warning "Decisions are one-way"

    There is no revocation state and no re-decision. A snapshot that is not
    `held` returns 409 from both endpoints.

---

## `GET /snapshots/{id}/provenance`

Where the snapshot came from and who decided on it.

```json
{"snapshotId":42,"marketplace":"acme",
 "upstreamUrl":"https://github.com/acme/skills.git","upstreamSha":"3f9c2ab...",
 "state":"approved","violation":null,"ingestedAt":"...",
 "decidedBy":"alice@example.com","decidedAt":"..."}
```

**200** · **404** unknown snapshot.

Every action on this page also appends an entry to the
[audit ledger](audit.md) carrying the acting principal.
