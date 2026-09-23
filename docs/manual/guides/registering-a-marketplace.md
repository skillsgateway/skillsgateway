# Registering a marketplace

Registration tells the gateway which upstream repository it is willing to talk
to. It is the first trust boundary, and it fetches nothing.

If the skills are your organisation's own and there is no upstream to point at,
register a **hosted** marketplace instead and push to the gateway directly —
see [Publishing first-party skills](publishing-first-party-skills.md). The rest
of this page is about upstream marketplaces.

## In the portal

**Marketplaces** → **Register marketplace**. The dialog states the constraint up
front: "The gateway ingests the upstream default branch; the ref is not
selectable."

| Field | Rule |
| --- | --- |
| Name | `^[a-z0-9][a-z0-9_-]*$` — lowercase letters, digits, `-` and `_`; must not start with `-` or `_`. |
| Clone URL | A valid URL whose scheme is on the allowlist. |

## Over the API

```console
$ curl -X POST localhost:8080/api/v1/marketplaces \
    -H 'Content-Type: application/json' \
    -d '{"name":"acme","url":"https://github.com/acme/skills.git"}'
```

```json
{"id":1,"name":"acme","url":"https://github.com/acme/skills.git","createdAt":"...","warnings":[]}
```

Responses:

| Status | Cause |
| --- | --- |
| 201 | Registered. `warnings` names any non-blocking issue with the registration (see below); empty when there is none. |
| 400 | URL scheme not allowlisted, or a `ref` other than `main`. |
| 409 | A marketplace with that name already exists. |
| 422 | The name fails the pattern. |

## What is validated, and why

**The URL scheme** must be on `skills-gateway.allowed-url-schemes` (default
`http`, `https`). The check fails closed: a URL that does not parse, or carries
no scheme at all, is rejected rather than passed through. This is what keeps
`file:` and `ssh:` out of a component that will later clone the URL.

**The ref** must be absent or exactly `main`. You cannot register
`release/1.x`. Which ref is ingested is the gateway's decision — see
[Compatibility and allowlists](../reference/compatibility.md).

**The name** doubles as a path segment on the facade (`/git/acme`), so it is
constrained to a character set that cannot traverse directories.

## Duplicate upstream URLs

Tracking one upstream repository under two marketplace names is legitimate —
it is how you test a marketplace before promoting it — so the gateway never
refuses a repeated URL. It does warn: registering a clone URL that matches
another registered upstream marketplace returns 201 with the new marketplace
in `warnings`, naming every existing marketplace with the same URL, for
example `"url already registered as acme"`.

The comparison normalizes both URLs first — lowercased scheme and host, no
trailing slash, and no `.git` suffix — so `https://github.com/acme/skills`,
`https://GitHub.com/acme/skills/` and `https://github.com/acme/skills.git` are
all the same registration as far as the warning is concerned. The repository
path itself keeps its case, since most forges treat it as case-sensitive. The
portal's own client-side check uses the identical rule and asks you to
acknowledge the collision before it will submit the form; the warning in the
response is what reaches every other caller, including a script calling the
API directly.

## Ingesting the first snapshot

Registration captures nothing. Press **Ingest** on the marketplace card, or:

```console
$ curl -X POST localhost:8080/api/v1/marketplaces/acme/ingest
```

```json
{"id":1,"marketplaceId":1,"sha":"3f9c2ab...","state":"held",
 "violation":null,"createdAt":"...","decidedBy":null,"decidedAt":null}
```

The gateway clones the upstream default branch into quarantine, pins the tip
commit as `refs/snapshots/{sha}`, and creates a snapshot in state `held`.
Nothing is served yet.

| Status | Cause |
| --- | --- |
| 201 | Snapshot captured. |
| 404 | Unknown marketplace. |
| 502 | Ingestion failed — upstream unreachable, or the manifest was rejected. |

Ingesting the same upstream commit twice does not create a second snapshot.

## Keeping it current

There is no upstream watcher. Something outside the gateway decides when to look
for new commits — a cron job, a CI schedule, or a forge webhook calling the
ingest endpoint:

```console
$ curl -X POST -u ... https://skills.corp.example/api/v1/marketplaces/acme/ingest
```

!!! info "Ingestion is always safe to run"

    A new upstream commit produces a new **held** snapshot and does not touch
    what clients receive. Ingesting frequently costs quarantine storage, never
    availability.

## Removing a marketplace

A marketplace registered with a typo, one whose upstream is abandoned, and one
whose upstream moved all end the same way: remove it, and register again if
there is anything to register.

```console
$ curl -X DELETE -u ... https://skills.corp.example/api/v1/marketplaces/acme \
    -H 'Content-Type: application/json' -d '{"reason":"upstream moved"}'
```

Removal withdraws everything the marketplace serves and stops it being synced or
fetched, but keeps its snapshots and their history. It is API-only and needs an
administrator; see the [reference](../reference/api/marketplaces.md#delete-marketplacesname).

**An upstream that moved.** The URL cannot be changed — that would relabel the
provenance of content already approved from somewhere else — so remove the
marketplace and register the same name against the new URL. Clients keep their
clone URL. They see nothing served until a snapshot of the new upstream is
approved: the old approvals were decisions about a different upstream and do not
carry over.

!!! warning "What carries over, and what does not"

    - **Fetch grants carry over.** A token scoped to fetch `acme` fetches
      whichever marketplace is called `acme` now. That is what keeps clients
      working; revoke a token that should not reach the new one.
    - **Publication grants do not.** Removal takes `acme` out of every token's
      push scope, and the ledger names the tokens. A publisher of the new `acme`
      needs a token granted on it.
    - **Approver grants do not.** Grant again on the new marketplace.
    - **The declarative estate does, unless you change it.** A marketplace still
      declared in `skills-gateway.estate.marketplaces` is registered again, as a
      new marketplace, on the next reconciliation. Remove the declaration first.

    The removed marketplace's vetter toggles and chain settings are kept in the
    database but no longer listed; set them again on the new marketplace.

The ledger tells the two apart: every entry carries `marketplaceId` beside the
name. See [Audit ledger](../reference/api/audit.md#entry-fields).

## Forge metadata

Registration captures forge, project, description and last-upstream-update on a
best-effort basis. It is displayed on the marketplace detail page and is
informational only; nothing depends on it.

## Next

[Approving and rejecting snapshots](approving-snapshots.md).
