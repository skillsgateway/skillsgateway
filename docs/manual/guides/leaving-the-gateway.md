# Leaving the gateway

Skills Gateway is designed to be the only door to your approved skills. That is
the point of it, and it is also a fair thing to be nervous about. This page is
the answer to "how do we get our estate out if we stop using this?", written
down so the answer does not have to be taken on trust.

**There is no export button, deliberately** — see
[ADR 0014 — Estate export](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0014-estate-import-export.md).
There does not need to be one: everything the gateway holds is already in two
open formats you can read without this product — git repositories and a
PostgreSQL database.

## What "the estate" actually is

Two halves, and you need both:

| Half | What it is | Where it lives |
| --- | --- | --- |
| The content | Ordinary git repositories, one per marketplace | The storage backend |
| The decisions | Who approved which commit, what vetting found, every fetch | PostgreSQL |

The content alone is skills nobody vouched for. The decisions alone are vouching
for content you no longer have. Take both.

## The simplest exit: point at the upstreams

If all you want is the skills themselves, you already have them. Every
marketplace the gateway serves was ingested from an upstream git repository
whose URL is recorded in its registration, and every snapshot is pinned to an
upstream commit SHA. Nothing is rewritten except composite snapshots — those
built from a manifest declaring external plugin sources — and those record their
provenance in the commit message itself.

```console
$ curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/marketplaces
```

Clone those URLs directly and you are out, with no further steps. This is the
right exit when the gateway was a distribution mechanism rather than a system of
record.

## The full exit: content plus decisions

Use this when you need to prove *what was approved and by whom* — a migration to
another instance, or an archive that outlives the deployment.

### 1. Get the content as plain git repositories

On the `filesystem` backend the data volume already holds bare repositories that
`git clone` reads. Copy the volume.

On the `object-store` backend the bucket holds JGit DFS packs, which no git
client can clone. Convert them by running the
[storage migration](storage-backends.md#migrating-an-existing-deployment) in the
other direction — into `filesystem` — against a scratch volume:

```bash
java -jar skills-gateway-server-<version>.jar \
  --spring.main.web-application-type=none \
  --skills-gateway.data-dir=/exit \
  --skills-gateway.storage.backend=object-store \
  --skills-gateway.storage.object-store.bucket=skills-gateway \
  --skills-gateway.storage.object-store.region=eu-north-1 \
  --skills-gateway.storage.migration.enabled=true \
  --skills-gateway.storage.migration.to=filesystem
```

The source bucket is opened and never written, so this is safe to run against a
live estate's storage, and it verifies itself — it re-reads both sides and exits
non-zero naming any repository that did not match. `/exit` then holds bare
repositories, in all three roles.

!!! warning "That includes quarantine"

    The migration copies every repository in every role, and quarantine holds
    ingested content nobody has reviewed. If you are handing the result to
    someone else, take `published/` and leave the rest.

### 2. Get the decisions

A database dump. It carries the registrations, every snapshot and its state,
attribution for who ingested and who approved, all vetting runs and findings,
waivers, overrides, and the audit ledger.

```console
$ pg_dump --format=custom --file=estate.dump "$DATABASE_URL"
```

The columns that establish an approval are `snapshots.state`,
`snapshots.ingested_by` and `snapshots.decided_by` — the last two kept distinct
because they are what the four-eyes rule compares. The schema itself lives in
`src/main/resources/db/migration/`, commented throughout.

!!! danger "The dump contains credentials"

    Access token hashes, the inbound webhook HMAC key, and outbound signing
    secrets are all in there. A dump is a backup, not something to hand to a
    third party. Rotate anything that leaves your control.

### 3. The ledger, if that is all you need

For an auditor who wants the history and not the content, the ledger streams out
over the API in NDJSON, with no database access:

```console
$ curl -H "Authorization: Bearer $TOKEN" \
    "localhost:8080/api/audit/export?limit=10000" > ledger.ndjson
```

See [Exporting the audit ledger](exporting-the-audit-ledger.md).

## What does not come back

Going *out* is straightforward. Coming *in* is not, and it is worth knowing
before you plan a migration: **approvals do not transfer.** Loading this content
into another Skills Gateway means re-approving it there, through that instance's
own gate, by identities that instance trusts.

That is deliberate rather than a missing feature. A file cannot prove who
approved what — so anything a gateway imports arrives unreviewed, and an import
that could write "approved" would be a way past the approval gate, which is the
one thing the product exists to prevent. The dump above is a restore of *this*
deployment, not a transfer to a different one.

## See also

- [Choosing and migrating the storage backend](storage-backends.md) — the
  migration this page borrows, and the disaster-recovery story
- [Exporting the audit ledger](exporting-the-audit-ledger.md)
- [ADR 0014 — Estate export](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0014-estate-import-export.md) — why there is no
  export feature, and what would reopen the question
