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
order, the findings behind it, and the fail-closed aggregate that gates
approval. A snapshot the chain has never run against reports
`"outcome":"BLOCKED"` and `"run":null`.

```json
{"snapshotId":12,"outcome":"BLOCKED",
 "run":{"runId":5,"snapshotId":12,"trigger":"ingestion","outcome":"BLOCKED",
        "startedAt":"...","finishedAt":"...",
        "overrideBy":null,"overrideAt":null,"overrideReason":null,
        "verdicts":[
          {"verdictId":9,"connector":"secret-scan","position":0,"state":"FAIL",
           "detail":"1 finding(s); worst critical","reportUrl":null,
           "findings":[{"id":"aws-access-key-id","severity":"CRITICAL",
                        "location":"plugins/hello/DEPLOY.md:5",
                        "message":"an AWS access key id is committed in this file"}]},
          {"verdictId":10,"connector":"prompt-injection","position":1,
           "state":"PASS","detail":null,"reportUrl":null,"findings":[]}]},
 "connectors":[{"name":"secret-scan","order":100,"description":"..."},
               {"name":"prompt-injection","order":200,"description":"..."}]}
```

`state` is one of `PASS`, `WARN`, `FAIL`, `ERROR`, `PENDING`; `severity` is one
of `INFO`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`; `outcome` is `CLEAR` or
`BLOCKED`. What each means is described in
[Vetting — the connector chain](../../concepts/vetting.md).

| Status | Cause |
| --- | --- |
| 200 | The latest chain run and the configured chain. |
| 404 | Unknown snapshot. |

---

## `POST /snapshots/{id}/approve`

**The only endpoint that publishes.** Fetches the pinned quarantine ref into the
published repository and force-updates `refs/heads/main` to that SHA.

The body is optional:

```json
{"overrideReason":"documented dummy key in the fixtures directory"}
```

It is **required** when the snapshot's latest vetting chain run did not clear —
including when there is no run at all. Without it the request is refused, and
the problem document carries a `blockingConnectors` array. With it, the reason
is recorded against the chain run (with the approving identity and time) and
appended to the audit ledger as `snapshot-approved-override`.

| Status | Cause |
| --- | --- |
| 200 | Approved; returns the snapshot with `decidedBy` and `decidedAt`. |
| 404 | Unknown snapshot. |
| 409 | The snapshot is not `held`, or its vetting chain blocked and no `overrideReason` was given. |

!!! warning "A blocked snapshot can still be published"

    The gate is a written, attributed reason — not a prohibition. Approving with
    an override publishes the content exactly as an ordinary approval does. The
    difference is that the ledger says who accepted the risk and why.

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
