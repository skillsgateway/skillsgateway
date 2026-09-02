# Marketplaces and snapshots

The core API: registration, ingestion, the approval gate and provenance. All
paths are relative to `/api`.

With [role enforcement](../../guides/delegated-administration.md) enabled:
registration and sync-mode changes require **admin**; ingest, approve, reject,
re-vet, and waiver create/delete require **approver of the marketplace** (or
admin) — resolved server-side from the addressed snapshot or waiver where the
route carries an id; every `GET` on this page stays open to any session, except
the connector settings, which are **admin**. Two administrative escape hatches
require **admin** specifically: overriding a blocked vetting outcome on approve,
and enabling or disabling a connector.

**Machine reach.** `marketplaces:read` covers `GET /marketplaces`, `GET
/catalog` and a snapshot's `/content`, `/content-diff`, `/licenses`,
`/provenance` and `/release-age`; `snapshots:read` covers `/diff`, `/file`,
`/files`, `/vetting`, `/fetchers` and `/four-eyes`; `marketplaces:register`,
`marketplaces:ingest`, `vetting:run`, `sync:write` and `waivers:read` cover
the corresponding mutations and the waiver listing. **Approve, reject, waiver
create, waiver delete, snapshot delete and snapshot restore are reachable by no
scope at all** — they publish, refuse or retract content. See
[Machine API credentials](tokens.md#machine-api-credentials).

## Representations

**Marketplace**

```json
{"id":1,"name":"acme","url":"https://github.com/acme/skills.git",
 "createdAt":"2026-08-15T09:00:00Z","registeredBy":"dana",
 "forge":"github","forgeProject":"acme/skills",
 "description":"Acme internal skills","upstreamUpdatedAt":"2026-08-14T18:20:00Z",
 "snapshots":[]}
```

**Snapshot**

```json
{"id":42,"marketplaceId":1,"sha":"3f9c2ab...","state":"held",
 "violation":null,"createdAt":"2026-08-15T09:01:00Z","ingestedBy":"ingrid",
 "decidedBy":null,"decidedAt":null}
```

`registeredBy` and `ingestedBy` are the supply-side identities the
[four-eyes rule](../../guides/approving-snapshots.md#separation-of-duties)
compares a reviewer against. `ingestedBy` is a principal for an on-demand
ingest or a push, `scheduler` or `webhook` for an automated trigger, and `null`
for a snapshot ingested before the actor was recorded; `registeredBy` is `null`
for a marketplace registered before it was.

`state` is one of `held`, `approved`, `rejected`, `revoked`. A revoked snapshot
also carries `revokedBy` and `revokedAt`, and its `violation` says what
re-vetting found. See
[Re-vetting approved content](../../guides/re-vetting.md).

---

## `POST /marketplaces`

Register a marketplace. Fetches nothing.

**Body** — `{name, url?, ref?, origin?, pushPolicy?}`

```console
$ curl -X POST localhost:8080/api/marketplaces \
    -H 'Content-Type: application/json' \
    -d '{"name":"acme","url":"https://github.com/acme/skills.git"}'
```

`origin` decides where the content comes from:

| `origin` | `url` | Content arrives by |
| --- | --- | --- |
| `upstream` (default) | required | the gateway fetching the upstream default branch |
| `hosted` | must be absent | a publisher pushing to `/publish/{name}` |

`pushPolicy` applies to a hosted marketplace only: `append-only` (the default)
refuses a non-fast-forward push, `allow-rewrite` permits one and records both
tips on the ledger. See
[Publishing first-party skills](../../guides/publishing-first-party-skills.md).

| Status | Cause |
| --- | --- |
| 201 | Registered; returns the marketplace. |
| 400 | URL scheme not allowlisted, `ref` present and not `main`, a hosted registration supplying a `url`, an upstream one omitting it, or a `pushPolicy` on an upstream marketplace. |
| 409 | Name already exists. |
| 422 | Name fails `^[a-z0-9][a-z0-9_-]*$`, or an unknown `origin`/`pushPolicy`. |

The 400 cases are trust-boundary rejections — see
[Compatibility and allowlists](../compatibility.md).

---

## `GET /marketplaces`

All marketplaces, each with its forge metadata and full snapshot list. This is
the portal's primary query; there is no per-marketplace endpoint.

**200** — array of marketplaces.

---

## `POST /marketplaces/{name}/ingest`

Clone the source's default branch into quarantine and pin the tip commit as
`refs/snapshots/{sha}`. Creates a snapshot in state `held`. For a hosted
marketplace the source is its own origin repository, and a push already does
this — the endpoint stays available to re-ingest.

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

## Upstream sync

Automated ingestion triggers (GW_0056–GW_0060). Modes and the secret lifecycle
are described in [Syncing from upstream automatically](../../guides/upstream-sync.md).

### `PUT /marketplaces/{name}/sync`

Set how upstream content reaches quarantine: `on-demand` (default),
`scheduled`, or `webhook`. No mode bypasses approval.

```console
$ curl -X PUT localhost:8080/api/marketplaces/acme/sync \
    -H 'Content-Type: application/json' -d '{"mode":"webhook"}'
```

```json
{"marketplace":{"name":"acme","syncMode":"webhook","...":"..."},
 "webhookSecret":"9f2c...ab41"}
```

`webhookSecret` is present only when the new mode is `webhook`, and only in
this response — setting `webhook` mode again rotates it, and leaving the mode
discards it. The change is audit-logged as `sync-mode-changed`.

| Status | Cause |
| --- | --- |
| 200 | Mode changed; returns the marketplace, plus the secret when entering webhook mode. |
| 404 | Unknown marketplace. |
| 422 | Not one of the three modes. |

### `POST /hooks/{marketplace}`

The inbound forge webhook — **not** under `/api`, and the only endpoint
reachable without an OIDC session or a PAT. Authenticated solely by an
HMAC-SHA256 signature of the exact raw request body in the GitHub-compatible
`X-Hub-Signature-256: sha256=<hex>` header, verified in constant time against
the marketplace's secret.

The payload is ignored: a valid signature only triggers an asynchronous
ingestion of the **registered** upstream URL's default branch — nothing in the
body is read. The ingestion is recorded on the ledger with the actor `webhook`
and lands `held` like any other.

| Status | Cause |
| --- | --- |
| 202 | Signature valid; ingestion queued. |
| 403 | Missing or invalid signature. Nothing was ingested. |
| 404 | Unknown marketplace, or its sync mode is not `webhook`. |
| 413 | Body exceeds `skills-gateway.sync.max-webhook-body-bytes`; rejected before verification. |

---

## Virtual catalog

The synthesized one-URL catalog (GW_0061–GW_0063); see
[The virtual catalog](../../guides/virtual-catalog.md).

### `GET /catalog`

The catalog revision the facade is serving at `/git/{catalog-name}`.

```json
{"sha":"a91b...","generatedAt":"...","constituents":[
  {"marketplace":"acme","sha":"3f9c..."}]}
```

**200** · **404** catalog disabled or not generated yet.

### `POST /catalog/rebuild`

Regenerate now from what every marketplace is serving. Approvals and
revocations already do this on their own; this is the on-demand repair path.
Audit-logged as `catalog-rebuilt` with the acting identity.

**200** — the new revision · **404** catalog disabled.

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

## `GET /snapshots/{id}/content-diff`

The same inventory, against the marketplace's newest live `approved` snapshot
other than this one: what approving this snapshot would add to what the
organisation already accepted. Works on `held` snapshots for the same reason
the inventory does.

Every plugin and skill on either side is returned with a status, so a client
can render either the whole inventory or only the changes.

| Field | Meaning |
| --- | --- |
| `baselineSnapshotId`, `baselineSha` | The approved snapshot compared against, or `null` when the marketplace has none |
| `plugins[].status` | `added`, `removed`, `changed` or `unchanged` — `changed` when any skill under it differs or when its manifest entry does |
| `plugins[].skills[].status` | `added`, `removed`, `changed`, `moved` or `unchanged` |
| `plugins[].skills[].movedFromPlugin` | The plugin the skill was declared under before it moved, else `null` |
| `summary` | Skill counts per status |

A skill is `changed` when anything under its directory differs — not only its
`SKILL.md` — because the git tree object of the directory is what is compared.
A skill one plugin gave up and another took over is reported once, on its new
plugin, as `moved`; if its content changed too, the status is `changed` and
`movedFromPlugin` is still set. With no approved snapshot, the baseline fields
are `null` and everything is `added`.

```json
{"snapshotId":43,"sha":"7c1d4ef...","state":"held",
 "baselineSnapshotId":42,"baselineSha":"3f9c2ab...",
 "plugins":[{"name":"acme-tools","description":"...","source":"./plugins/acme-tools",
             "status":"changed",
             "skills":[{"name":"deploy","path":"plugins/acme-tools/skills/deploy/SKILL.md",
                        "status":"unchanged","movedFromPlugin":null},
                       {"name":"rollback","path":"plugins/acme-tools/skills/rollback/SKILL.md",
                        "status":"moved","movedFromPlugin":"acme-legacy"}]}],
 "summary":{"added":0,"removed":0,"changed":0,"moved":1,"unchanged":1}}
```

!!! note
    This is not the [preview diff](#get-snapshotsiddiff). That one is a
    file-level unified diff against the commit the facade is **currently
    serving**; this one is an inventory diff against the last commit that was
    **approved**, and returns no file text.

**200** · **404** unknown snapshot.

---

## `GET /snapshots/{id}/licenses`

The licenses the snapshot declares, each with its standing under the
[configured allow/ban policy](../configuration.md#vetting). Detection is
deterministic — SPDX ids resolved from license/copying files anywhere in the
tree, `SPDX-License-Identifier` tags inside them, and the marketplace
manifest's `license` metadata fields — and runs over the content pinned to the
snapshot's commit SHA, so the report exists for every snapshot, `held`
included.

```json
{"snapshotId":42,"sha":"3f9c2ab...",
 "licenses":[
   {"spdxId":"MIT","source":"file","location":"LICENSE",
    "declared":null,"evaluation":"OK"},
   {"spdxId":null,"source":"file","location":"plugins/acme-tools/COPYING",
    "declared":null,"evaluation":"UNKNOWN"},
   {"spdxId":"ISC","source":"manifest",
    "location":".claude-plugin/marketplace.json#plugins[acme-tools].license",
    "declared":"ISC","evaluation":"OK"}],
 "allowed":["MIT","Apache-2.0"],"banned":["AGPL-3.0"]}
```

A `spdxId` of `null` is the **unknown license** state — the source identified
no known license. An empty `licenses` array means the snapshot carries no
license information at all. `evaluation` is one of `OK`, `BANNED`,
`NOT_ALLOWED`, `UNKNOWN`.

This read reports **current policy truth**: it is recomputed under the
configuration in force, complementing the gateway's own
[SBOM endpoint](index.md#non-api-endpoints) (`/actuator/sbom`) and the content
inventory above as the supply-chain read surface. The evidence the approval gate acted on is the
recorded vetting run, not this report.

**200** · **404** unknown snapshot.

---

## Snapshot preview

Read-only inspection of a snapshot's pinned content: the file tree, individual
files, and the diff against what the marketplace currently serves. Everything
resolves through the quarantine repository's object store at the pinned commit
— paths are matched against tree entries only, so a path the commit does not
contain (traversal shapes included) is simply not found. Works on `held`
snapshots: inspecting content must not require serving it.

These reads return raw held content, so unlike the metadata reads above they
are **privileged**: an admin, or an approver of the snapshot's marketplace.
Everyone else gets **403**.

### `GET /snapshots/{id}/files`

Every path in the pinned commit's tree, with blob sizes. The listing is capped
at 2000 entries; `"truncated": true` says it was cut.

```json
{"snapshotId":42,"sha":"3f9c2ab...","truncated":false,
 "entries":[{"path":".claude-plugin/marketplace.json","size":180},
            {"path":"plugins/acme-tools/skills/deploy/SKILL.md","size":841}]}
```

**200** · **403** enforcement enabled, no applicable role · **404** unknown
snapshot.

### `GET /snapshots/{id}/file?path={path}`

One blob, as text for rendering only. Content is cut at 128 KiB with
`"truncated": true` and the full `size` still reported; a blob detected as
binary returns metadata with no `text` at all.

```json
{"snapshotId":42,"path":"plugins/acme-tools/skills/deploy/SKILL.md",
 "size":841,"binary":false,"truncated":false,"text":"# Deploy\n..."}
```

**200** · **403** enforcement enabled, no applicable role · **404** unknown
snapshot, or the path is not in the pinned tree.

### `GET /snapshots/{id}/diff`

The delta a reviewer decides: added, modified and removed paths between the
pinned commit and the marketplace's currently served commit (the published
repository's served tip — the same commit a `git fetch` returns), with a
unified text diff per non-binary entry under the same 128 KiB cap. The entry
list is capped at 500 with a `truncated` marker.

When the marketplace serves nothing — never approved, or its content was
revoked or unpublished — `baselineSha` is `null` and every path is reported as
`added`, without diff text: approving the snapshot would serve all of it.

```json
{"snapshotId":43,"sha":"9d41f00...","baselineSha":"3f9c2ab...","truncated":false,
 "entries":[{"path":"plugins/acme-tools/skills/deploy/SKILL.md","type":"modified",
             "binary":false,"truncated":false,
             "diff":"--- a/...\n+++ b/...\n@@ -1 +1 @@\n-old\n+new\n"}]}
```

**200** · **403** enforcement enabled, no applicable role · **404** unknown
snapshot.

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
| `override` | Present when an administrator approved this snapshot over a blocked outcome (`reason`, `blockingConnectors`, `uncoveredFindings`, `overriddenBy`, `overriddenAt`); `null` otherwise. Its presence is what surfaces the override so it is never indistinguishable from a clean approval. See [The vetting override](#administrative-override-of-a-blocked-outcome). |

`state` is one of `PASS`, `WARN`, `FAIL`, `ERROR`, `PENDING`, `DISABLED`;
`severity` is one of `INFO`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. A `DISABLED`
verdict records that an administrator switched that connector off for the
snapshot's marketplace ([connector settings](#connector-enabledisable)); it is
neither clearing nor blocking, but a run still needs one clearing verdict to
clear, so disabling every connector leaves a run `BLOCKED`. What each state means
is described in [Vetting — the connector chain](../../concepts/vetting.md).

| Status | Cause |
| --- | --- |
| 200 | The latest chain run, its waivers, and the configured chain. |
| 404 | Unknown snapshot. |

---

## Connector enable/disable

An administrator can switch a built-in connector (secret-scan, prompt-injection,
license-scan) off or on, globally or for one marketplace. Both endpoints are
**admin-only** — the switch that governs the vetting chain, and even the
visibility of its settings, are not shown to marketplace-scoped approvers.

A disabled connector is **not run** at ingestion or re-vetting; the chain records
a `DISABLED` verdict in its place, so the disablement is part of the run's
evidence rather than a silently shorter chain. Disabling every connector leaves a
run `BLOCKED`, never cleared — the switch is not a blanket approval.

### `GET /vetting/connector-toggles`

Lists every enable/disable setting — the global settings and the per-marketplace
overrides.

| Status | Cause |
| --- | --- |
| 200 | The connector settings. |
| 403 | Caller does not hold the administrative role. |

### `PUT /vetting/connectors/{name}/toggle`

| Field | Required | Meaning |
| --- | --- | --- |
| `enabled` | yes | `true` to run the connector, `false` to switch it off. |
| `marketplace` | no | Scope the setting to one marketplace; omit for the global setting. A per-marketplace setting overrides the global one. |
| `reason` | no | A note recorded with the change and on the audit ledger. |

Each toggle is audited (`connector-disabled` / `connector-enabled`) naming the
administrator, the connector, the scope and the new state.

| Status | Cause |
| --- | --- |
| 200 | The setting after the change. |
| 403 | Caller does not hold the administrative role. |
| 404 | Named marketplace not found. |
| 422 | Unknown connector, or `enabled` omitted. |

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

## Continuous re-vetting

Re-running the chain over content that is **already approved**. What a fresh
violation does is a deployment setting, not a request parameter — see
[Re-vetting approved content](../../guides/re-vetting.md) and the
[`revet` configuration block](../configuration.md#continuous-re-vetting).

### `POST /snapshots/{id}/revet`

Runs the chain again over the snapshot's pinned content, recording a new run
with trigger `revet-manual`.

```json
{"snapshotId":12,"marketplace":"corp-marketplace","sha":"3f9c2ab…","runId":31,
 "classification":"VIOLATION","outcome":"BLOCKED","revoked":true,"mode":"ENFORCE",
 "uncovered":[{"connector":"secret-scan","ruleId":"aws-access-key-id",
               "location":"plugins/hello/DEPLOY.md:5","severity":"CRITICAL",
               "message":"an AWS access key id is committed in this file"}],
 "affected":[{"principal":"team-payments","fetches":12,
              "lastFetch":"2026-08-14T22:10:00Z"}]}
```

| Field | Meaning |
| --- | --- |
| `classification` | `CLEAR`, `VIOLATION` (the chain objects to the content), or `INCONCLUSIVE` (it blocks only because a connector errored, timed out, or has not answered). |
| `outcome` | The effective vetting outcome after the waivers active at that instant. |
| `revoked` | Whether this run revoked and unpublished the snapshot. Always `false` in `warn` mode and for `INCONCLUSIVE`. |
| `mode` | The re-vetting mode in force. |
| `uncovered` | The blocking findings no active waiver covers — the reason for a violation. |
| `affected` | Identities that had already fetched this snapshot's content. |

| Status | Cause |
| --- | --- |
| 200 | The run and what it concluded. |
| 404 | Unknown snapshot. |
| 409 | The snapshot is not `approved`. Only served content is re-vetted. |

### `POST /marketplaces/{name}/revet`

The same, over every live approved snapshot of the marketplace. This is the
operational answer to a scanner rule set or advisory feed that has just moved:
the built-in connectors have no feed to subscribe to, so an operator calling
this after updating one is how a feed update becomes fresh evidence.

Returns a pass summary — `revetted`, `violations`, `revoked`, `inconclusive`,
and the individual `results`.

| Status | Cause |
| --- | --- |
| 200 | What the pass re-vetted and concluded. |
| 404 | Unknown marketplace. |

### `GET /snapshots/{id}/fetchers`

Every authenticated identity that **received** this snapshot's content through
the git facade, with how often and when it last did — the blast radius of a
retroactive violation, read from the append-only fetch ledger.

```json
[{"principal":"team-payments","fetches":12,"lastFetch":"2026-08-14T22:10:00Z"}]
```

Only pack transfers count. A ref advertisement means a client asked, not that it
received anything, so counting it would name teams that never got the content.

| Status | Cause |
| --- | --- |
| 200 | The identities that fetched the snapshot's content. |
| 404 | Unknown snapshot. |

---

## `POST /snapshots/{id}/approve`

**The only endpoint that publishes.** Fetches the pinned quarantine ref into the
published repository and force-updates `refs/heads/main` to that SHA.

**The request body is optional.** A snapshot whose effective vetting outcome is
blocked is refused, and the problem document carries both `blockingConnectors`
and `uncoveredFindings`:

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

A snapshot inside the configured
[minimum release age](../configuration.md#minimum-release-age) is refused by the
same status with a different problem document — one nothing in the API can
unblock, because it clears itself:

```json
{"status":409,"title":"Snapshot has not reached the minimum release age",
 "detail":"snapshot 12 cannot be approved yet: …",
 "configKey":"skills-gateway.vetting.minimum-release-age",
 "minimumReleaseAge":"PT72H",
 "eligibility":{"snapshotId":12,"eligible":false,
                "firstIngestedAt":"2026-08-16T09:00:00Z",
                "eligibleAt":"2026-08-19T09:00:00Z",
                "ageSeconds":57600,"remainingSeconds":187200,
                "minimumReleaseAgeSeconds":259200}}
```

Under an enforcing
[four-eyes rule](../../guides/approving-snapshots.md#separation-of-duties) an
approval by an identity on the snapshot's supply side is refused by the same
status, with the conflicting acts named:

```json
{"status":409,"title":"Four-eyes rule refused this approval",
 "detail":"four-eyes rule refused approval of snapshot 12: …",
 "configKey":"skills-gateway.approval.four-eyes.mode",
 "conflicts":[{"role":"registered-by","principal":"dana","waiverId":null},
              {"role":"ingested-by","principal":"dana","waiverId":null},
              {"role":"waiver-author","principal":"dana","waiverId":7}]}
```

Nothing in the API unblocks this one either: a different identity has to
approve. Under the default `warn` mode the same conflicts are detected, the
approval succeeds, and a `four-eyes-conflict` entry is appended to the audit
ledger beside `snapshot-approved`.

| Status | Cause |
| --- | --- |
| 200 | Approved; returns the snapshot with `decidedBy` and `decidedAt`. |
| 404 | Unknown snapshot. |
| 409 | The snapshot is neither `held` nor `revoked`, its effective vetting outcome is blocked and no override was supplied, a [policy rule](policy.md) denied it, it has not reached the minimum release age, or an enforcing four-eyes rule refused it. |
| 422 | An override was requested (`overrideVetting: true`) without a `reason`. |

A `revoked` snapshot is approved through this same endpoint and no other — there
is no un-revoke. The gate is unchanged, so the finding that revoked it must be
waived or fixed first; the transition records a fresh reviewer and clears the
revocation marks.

### Administrative override of a blocked outcome

The airline-cockpit escape hatch: an **administrator — and only an
administrator** — may approve a snapshot whose effective outcome is blocked by
sending a body:

```json
{"overrideVetting": true, "reason": "vendor-signed key, accepted risk in TICKET-42"}
```

The override **lifts only the vetting gate** — the policy, minimum-release-age
and four-eyes gates still run. A `reason` is required (a reasonless override is
`422`). The override writes a distinct audit event,
`snapshot-approved-over-vetting-failure`, naming the administrator, the reason
and the blocking verdicts, and it marks the snapshot so `GET
/snapshots/{id}/vetting` reports an `override` — an override is never
indistinguishable from an approval the chain cleared. A marketplace-scoped
approver, who may approve a *clean* snapshot, cannot override a blocked one.

!!! warning "A blocked snapshot can still be published"

    There are two deliberate ways past a block, and each leaves its own trail. A
    **waiver** is a scoped, expiring acceptance of one finding that any reviewer
    may record; the ledger says which risk was accepted, by whom, and until when.
    An **override** is a one-off, whole-outcome act reserved to an administrator,
    who states a reason and is named on a distinct ledger event. Neither is a
    silent bypass.

---

## `GET /snapshots/{id}/release-age`

Whether the snapshot has cleared the
[minimum release age](../configuration.md#minimum-release-age), and when it will
if it has not. This is the same computation the approve endpoint gates on, so
the two can never disagree.

```json
{"snapshotId":12,"eligible":false,
 "firstIngestedAt":"2026-08-16T09:00:00Z","eligibleAt":"2026-08-19T09:00:00Z",
 "ageSeconds":57600,"remainingSeconds":187200,"minimumReleaseAgeSeconds":259200}
```

| Field | Meaning |
| --- | --- |
| `eligible` | Whether the snapshot may be approved now. Always `true` when the gate is off. |
| `firstIngestedAt` | When this gateway first ingested the commit. The age is counted from here — never from the commit's own timestamp. |
| `eligibleAt` | The instant it becomes approvable; equal to `firstIngestedAt` when the gate is off. |
| `ageSeconds` | How long ago the gateway first ingested the commit. |
| `remainingSeconds` | How much of the window is left; `0` when eligible. |
| `minimumReleaseAgeSeconds` | The configured window; `0` when the gate is off. |

| Status | Cause |
| --- | --- |
| 200 | The eligibility record above. |
| 404 | Unknown snapshot. |

---

## `GET /snapshots/{id}/four-eyes`

Whether the
[four-eyes rule](../../guides/approving-snapshots.md#separation-of-duties) would
object to **the calling identity** approving this snapshot, and what the
configured mode would do about it. Decides nothing; the approve endpoint
enforces the rule independently.

```json
{"mode":"ENFORCE","refused":true,
 "conflicts":[{"role":"registered-by","principal":"dana","waiverId":null},
              {"role":"ingested-by","principal":"dana","waiverId":null}]}
```

| Field | Meaning |
| --- | --- |
| `mode` | `WARN` or `ENFORCE`, as configured. There is no mode that disables detection. |
| `conflicts` | Each supply-side act the caller performed on this snapshot: `registered-by`, `ingested-by`, or `waiver-author` with the `waiverId` they wrote. Empty for an independent reviewer. |
| `refused` | Whether an approval by this caller would be refused — true only when the mode is `ENFORCE` and `conflicts` is non-empty. |

The waiver clause is evaluated exactly as an approval would evaluate it, over
the waivers that are actually suppressing findings on this snapshot right now —
which is why this is answered by the server rather than derived by a client.

| Status | Cause |
| --- | --- |
| 200 | The record above. |
| 404 | Unknown snapshot. |

---

## `POST /snapshots/{id}/reject`

Mark the snapshot `rejected`. No repository is touched; whatever was already
approved keeps serving. This is also the terminal answer to a `revoked`
snapshot nobody intends to waive.

Same status codes as approve, without its two refusals: neither the vetting
outcome nor the minimum release age gates a rejection. Saying no to suspicious
content is never something to wait for.

!!! warning "An approved snapshot cannot be re-decided"

    Both endpoints return 409 for a snapshot that is `approved` or `rejected`.
    The only way out of `approved` is an enforced re-vetting violation, which
    the gateway makes, not a caller.

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
