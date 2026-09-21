# Backing up, restoring and upgrading

The gateway keeps its state in two places, and **they are one estate**. Back them up
together or the backup is not one.

- **PostgreSQL** holds the governance state: which marketplaces exist, which snapshots
  were approved and by whom, the roles, the tokens, the append-only ledger.
- **The git storage** holds the content those rows describe — the quarantine
  repositories and the published ones — on either the `filesystem` backend's volume or
  the `object-store` backend's bucket prefix.

A gateway that restarts onto an empty volume still reports its snapshots as published
and can serve none of them. The database says the content is there; the storage
disagrees; nothing in the gateway can tell which of the two is right, because both are
authoritative about different halves of the same fact.

!!! danger "A split restore is silent"

    Restore the database from Monday and the storage from Tuesday and the gateway
    starts, serves most things, and is wrong about the rest. There is no check that
    fails. The pairing is the operator's to preserve, which is why every procedure
    below quiesces first.

## Taking a backup

The gateway does not need to be stopped for a *consistent-enough* copy if you accept
that a snapshot approved during the copy may land in one half and not the other. It
does need to be stopped for a copy you can restore without thinking about it.

### 1. Quiesce

```console
# Kubernetes
$ kubectl scale deployment/skills-gateway --replicas=0

# Compose or a single host
$ docker compose stop skills-gateway
```

Scaling to zero is enough: the gateway holds no lease it must release, and its
background sweeps take a `sweep_leases` row that expires on its own.

### 2. Copy both halves

```console
$ pg_dump --format=custom --file=gateway-$(date -I).dump "$DATABASE_URL"
```

Then, on the `filesystem` backend, the data directory — including the repositories:

```console
$ tar --create --file=gateway-data-$(date -I).tar.zst --zstd -C /data .
```

On the `object-store` backend, the bucket prefix:

```console
$ aws s3 sync "s3://$BUCKET/$PREFIX" "./gateway-objects-$(date -I)/"
```

!!! note "Bucket versioning is not a substitute"

    [Storage backends](storage-backends.md) records that the gateway's behaviour under
    bucket versioning is **unverified**. Treat the store's own point-in-time recovery as
    untested for this purpose and take the copy.

### 3. Start again

Reverse step 1. Keep the two files together — same directory, same date in the name.
The pairing is the only thing that makes them a backup.

## Restoring

Restore **both halves from the same moment**, into a gateway that is not running.

```console
$ dropdb skills_gateway && createdb skills_gateway
$ pg_restore --dbname="$DATABASE_URL" gateway-2026-09-21.dump
$ rm -rf /data/* && tar --extract --file=gateway-data-2026-09-21.tar.zst --zstd -C /data
```

Then start the gateway and check that what it claims to serve, it serves:

```console
$ curl -sS localhost:8080/actuator/health/readiness
$ git clone http://token:$PAT@localhost:8080/git/<a-marketplace>
```

A clone that fails against a marketplace the portal lists as published is the split
restore this page exists to prevent. Restore the other half from the same moment.

## Upgrading

### What happens at startup

**Flyway runs the schema migration when the application starts.** No separate step, no
flag. That has two consequences for a rollout:

1. **Upgrade with one replica at a time** on the `object-store` backend, and note that
   the `filesystem` backend is single-replica anyway — the chart's stop-then-start
   deployment strategy enforces it. Two versions of the application against one
   database, one of which is migrating it, is not a configuration the project tests.
2. **A migration is not reversible by restarting the old image.** Roll back by
   restoring the pair, not by redeploying the previous tag.

!!! warning "Before 1.0.0 there is no migration path at all"

    Until 1.0.0 the schema is a single migration that is *edited in place*, so its
    checksum changes between versions and Flyway refuses to start against a database
    created by a different build. Upgrading a pre-1.0 deployment means recreating the
    database, which means losing its state. Pre-1.0 deployments are for evaluation, and
    this is what that means in practice.

    From 1.0.0 the initial migration freezes and later changes arrive as additional
    migrations, which is when the procedure below starts to be the whole story.

### The procedure

```console
# 1. Take the backup above. Not optional: this is the rollback.
# 2. Roll the image forward.
$ helm upgrade skills-gateway ./helm/skills-gateway --set image.tag=<new>
# 3. Watch it come ready, which is also watching the migration succeed.
$ kubectl rollout status deployment/skills-gateway
```

A failed migration leaves the pod not ready and the old data untouched. Readiness
reports the database, so a pod that never becomes ready is the signal — there is no
need to read logs to find out whether the migration ran.

### What a rollout does to clients

A `git fetch` in flight during a rollout **completes**: the gateway drains on SIGTERM
rather than severing, and the pod's grace period is long enough to let it
(`terminationGracePeriodSeconds`, 60 seconds by default). Raise it if clients report
truncated fetches during upgrades, which means a transfer is outlasting the drain.

## What is not backed up

- **Nothing reconstructible.** The virtual catalog is derived from the approved set and
  rebuilt on demand.
- **Secrets held outside the gateway** — the OIDC client secret, the object store's
  credentials, the database password. Those belong to whatever manages them, and a
  restore needs them present before it starts.
- **The forge mirror.** It is an outbound copy, not a source
  ([The read-only forge mirror](read-only-forge-mirror.md)); a restored gateway
  re-mirrors.
