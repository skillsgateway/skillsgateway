# Adoption

Read-only reports derived from the append-only fetch ledger and the served
tips of the published repositories. Nothing here writes anything — the ledger
holds the raw entries, these endpoints aggregate them. For the raw feed see
[Audit](audit.md).

**Machine reach.** `adoption:read` covers every endpoint on this page. See
[Machine API credentials](tokens.md#machine-api-credentials).

Only `upload-pack` entries count: a ref advertisement (`info-refs`) fires on
every `git fetch` whether or not content transfers, so counting it would name
identities that never received anything.

!!! note "Identities, not teams"

    There is no team concept in the gateway. Both reports attribute by the
    authenticated identity (and, on the ledger, the token) that fetched.
    Mapping identities to teams is the identity provider's knowledge, and it
    is deliberately not reconstructed here.

!!! warning "Presence, not usage"

    The [presence report](#get-apiv1adoptionpresence) counts per skill, but a
    git fetch transfers a whole snapshot: every skill in one snapshot has that
    snapshot's identity count, by construction. It says who *holds* a skill,
    never who *used* it. The gateway does not ingest client usage telemetry
    ([ADR 0016](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0016-client-invocation-telemetry-is-not-ingested.md)),
    and **adherence** — whether the guidance was followed — is outside what
    the gateway can observe; that belongs to CI and policy gates in the
    consuming repositories.

---

Every read requires **auditor** (or admin) — they enumerate identities off the
ledger, exactly like the ledger reads themselves. See
[Delegated administration](../../guides/delegated-administration.md).

---

## `GET /api/v1/adoption`

The adoption report: per marketplace, the window's content-transferring
fetches, distinct fetching identities, the most recent fetch, and a
per-snapshot-SHA breakdown with each SHA marked current against the served tip.

| Parameter | Meaning |
| --- | --- |
| `days` | Report window in days. Default `30`; out-of-range values are clamped to `1..365`. |

```console
$ curl "localhost:8080/api/v1/adoption?days=30"
```

```json
[{"marketplace":"acme","servedSha":"3f9c2ab...","fetches":14,"identities":3,
  "lastFetch":"2026-08-15T09:04:11Z",
  "snapshots":[
    {"sha":"3f9c2ab...","fetches":9,"identities":3,
     "lastFetch":"2026-08-15T09:04:11Z","current":true},
    {"sha":"9d01c44...","fetches":5,"identities":2,
     "lastFetch":"2026-08-12T08:00:00Z","current":false}]}]
```

**200.** One entry per marketplace fetched in the window, ordered by name; the
[virtual catalog](../../guides/virtual-catalog.md) appears under its own name
like any marketplace. `servedSha` is `null` when the marketplace is no longer
serving. A marketplace nobody fetched in the window has no entry — the report
covers fetch activity, not the registry.

---

## `GET /api/v1/adoption/staleness`

Every identity whose most recent content-transferring fetch of a marketplace
received a SHA that is **not** that marketplace's currently served tip.
Window-free by design: staleness is a property of an identity's latest state,
not of a reporting period.

```console
$ curl localhost:8080/api/v1/adoption/staleness
```

```json
[{"principal":"team-payments","marketplace":"acme","sha":"9d01c44...",
  "lastFetch":"2026-08-12T08:00:00Z","servedSha":"3f9c2ab..."},
 {"principal":"ci-runner","marketplace":"retired","sha":"77aa310...",
  "lastFetch":"2026-08-10T06:00:00Z","servedSha":null}]
```

**200.** A `null` `servedSha` means the marketplace stopped serving entirely —
revoked or unpublished — so the identity holds retracted content; that is the
blast-radius case the report exists for.

!!! note "Facts, not verdicts"

    An identity may be pinned to an old SHA on purpose. The report states what
    was received and what is served; deciding whether that is a problem is the
    operator's call.

---

## `GET /api/v1/adoption/presence`

Skill-level presence: for every skill in any snapshot the facade delivered,
keyed by marketplace, plugin and skill, the identities whose latest fetch of
that marketplace holds it, and the snapshots that delivered it over what span.
It answers the recall question — *who holds `skill-x`* — without first working
out which marketplaces at which SHAs contained it.

| Parameter | Meaning |
| --- | --- |
| `since` | Optional ISO-8601 instant. Identities whose latest fetch predates it are left out of the counts, for an estate where older installs are presumed decommissioned. It never narrows which deliveries are considered. Omitted means all time. |

```console
$ curl localhost:8080/api/v1/adoption/presence
```

```json
{"measure":"presence",
 "statement":"Presence, not invocation: these counts say which identities received a skill ...",
 "since":null,
 "skills":[
   {"marketplace":"acme","plugin":"toolkit","skill":"code-review",
    "path":"plugins/toolkit/skills/code-review/SKILL.md",
    "identitiesHolding":3,"snapshotsDelivering":2,
    "firstDelivered":"2026-08-01T09:00:00Z","lastDelivered":"2026-08-15T09:04:11Z",
    "snapshots":[
      {"sha":"3f9c2ab...","identitiesHolding":2,"lastFetch":"2026-08-15T09:04:11Z","current":true},
      {"sha":"9d01c44...","identitiesHolding":1,"lastFetch":"2026-08-12T08:00:00Z","current":false}]}],
 "unresolved":[
   {"marketplace":"acme","sha":"5e1f0aa...","identitiesHolding":1,
    "firstDelivered":"2026-06-02T07:00:00Z","lastDelivered":"2026-06-03T07:00:00Z","current":false}]}
```

**200.** Window-free like staleness: an identity holds what its most recent
fetch of a marketplace received, so it counts against exactly one SHA per
marketplace. `measure` and `statement` are part of the payload so that an
export carries its own caveat.

- **Only delivered snapshots.** The SHAs come from `upload-pack` ledger entries
  alone, never from a snapshot id the caller names, so a held snapshot's
  contents are never named here. Names and paths only; file content stays behind
  [snapshot file inspection](marketplaces.md).
- **`unresolved` is not empty.** A delivered SHA whose objects retention
  reclaimed, or whose manifest no longer parses, is listed with its holders
  instead of being dropped. Its holders may hold any skill.
- **The ledger trim keeps what this reads.** The
  [ledger trim](../../guides/snapshot-retention.md#7-bound-the-audit-ledger)
  never removes an identity's latest `upload-pack` of a marketplace, so trimming
  does not drop holders. It can remove earlier deliveries, so `firstDelivered`
  may be later than the true first fetch on a trimmed ledger.
- **Who, by name.** The counts do not name identities. For the holders of one
  snapshot use [`GET /api/v1/snapshots/{id}/fetchers`](marketplaces.md), which
  is gated to that marketplace's approvers.
- **Joining with client telemetry.** `marketplace`, `plugin` and `skill` are the
  names as served, the keys an organisation's own metrics backend would join
  invocation data on. Whether a client reports them unredacted is up to the
  client ([ADR 0016](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0016-client-invocation-telemetry-is-not-ingested.md)).

A snapshot's content is resolved once per process and cached; the
`skills_gateway.adoption.presence_cache` counter (`result=hit|miss`) shows how
often the report reads a commit tree.
