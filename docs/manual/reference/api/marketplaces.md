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

## `POST /snapshots/{id}/approve`

**The only endpoint that publishes.** Fetches the pinned quarantine ref into the
published repository and force-updates `refs/heads/main` to that SHA.

| Status | Cause |
| --- | --- |
| 200 | Approved; returns the snapshot with `decidedBy` and `decidedAt`. |
| 404 | Unknown snapshot. |
| 409 | The snapshot is not `held`. |

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
