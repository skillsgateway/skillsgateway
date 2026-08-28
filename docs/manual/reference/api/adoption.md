# Adoption

Read-only reports derived from the append-only fetch ledger and the served
tips of the published repositories. Nothing here writes anything — the ledger
holds the raw entries, these endpoints aggregate them. For the raw feed see
[Audit](audit.md).

**Machine reach.** `adoption:read` covers both endpoints on this page. See
[Machine API credentials](tokens.md#machine-api-credentials).

Only `upload-pack` entries count: a ref advertisement (`info-refs`) fires on
every `git fetch` whether or not content transfers, so counting it would name
identities that never received anything.

!!! note "Identities, not teams"

    There is no team concept in the gateway. Both reports attribute by the
    authenticated identity (and, on the ledger, the token) that fetched.
    Mapping identities to teams is the identity provider's knowledge, and it
    is deliberately not reconstructed here.

---

With [role enforcement](../../guides/delegated-administration.md) enabled, both
reads require **auditor** (or admin) — they enumerate identities off the
ledger, exactly like the ledger reads themselves.

---

## `GET /api/adoption`

The adoption report: per marketplace, the window's content-transferring
fetches, distinct fetching identities, the most recent fetch, and a
per-snapshot-SHA breakdown with each SHA marked current against the served tip.

| Parameter | Meaning |
| --- | --- |
| `days` | Report window in days. Default `30`; out-of-range values are clamped to `1..365`. |

```console
$ curl "localhost:8080/api/adoption?days=30"
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

## `GET /api/adoption/staleness`

Every identity whose most recent content-transferring fetch of a marketplace
received a SHA that is **not** that marketplace's currently served tip.
Window-free by design: staleness is a property of an identity's latest state,
not of a reporting period.

```console
$ curl localhost:8080/api/adoption/staleness
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
