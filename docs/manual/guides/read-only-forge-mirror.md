# The read-only forge mirror

The gateway can push a copy of approved content to a repository on an external
code host, so people can browse, search and read it there instead of cloning it
to look at it. It is off by default and a deployment that never turns it on
performs no outbound push at all.

!!! danger "The mirror is a browsing convenience, and nothing else"

    Agent installs and CI keep pointing at the facade. A fetch from the mirror is
    not on the audit ledger, is not covered by the gateway's access tokens, and
    tells nobody anything about who is using what.

    This is a decision, not an omission. The gateway's serving surface is the
    facade because the approval gate and the fetch ledger are enforced there and
    cannot be enforced on a repository other people can write to — see
    [ADR 0008](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0008-serving-surface-stays-the-embedded-facade.md).

## What it does

Whenever a snapshot is approved or revoked — **and on a recurring schedule
regardless** — the gateway makes the mirror hold exactly the references the
facade serves for that marketplace (`refs/heads/main` and `refs/snapshots/*`) and
removes anything it still holds in those namespaces that is no longer served.
Nothing else on the mirror is touched: a branch, a tag or a README the repository
already has is left alone.

Three consequences fall out of that shape and are worth stating plainly:

- **Revocation reaches the mirror.** A revoked snapshot's references are gone
  from the mirror as well as from the facade, so nobody can pull the withdrawn
  content out of the copy.
- **A failed update is drift, never a blocked decision.** If the mirror cannot
  be reached, the approval or revocation still happens in full, the facade
  behaves exactly as it would with the mirror off, and the divergence shows up
  in the drift report.
- **That drift does not last indefinitely.** The recurring reconciliation runs
  whether or not anything was approved, so a push that gave up, an update lost to
  a restart and a change somebody made on the host by hand are all corrected
  within `sweep-interval` — 15 minutes by default. This is why a failed push is
  allowed to simply stop rather than retry forever.

!!! warning "A stale mirror is a security problem, not a cosmetic one"

    A mirror that still holds a revoked snapshot is a way to read content the
    gateway has withdrawn. That is why the reconciliation is on a timer rather
    than only on approvals, and why
    [`skills_gateway.mirror.stale_refs`](../reference/observability.md#the-read-only-forge-mirror)
    is the meter to alert on.

## 1. Create the repository on the host

The gateway does not create it, and never calls a code host's API. Make an empty
repository by hand, and give the account the gateway will push as write access to
it and nothing else.

Two things worth doing while you are there, since the gateway does not do them
either:

- Put a line at the top of the repository description, or in a README on a branch
  the gateway does not touch, saying that it is a read-only mirror and where the
  real clone URL is.
- Make it read-only for everybody else. Content that appears there did pass the
  approval gate; content somebody pushes there directly did not. The gateway
  removes it at the next reconciliation — within `sweep-interval` even if nothing
  is approved — and records `mirror-drift-repaired` naming the references it took
  off, so the fact that somebody wrote there is not erased by the repair.

## 2. Configure the gateway

```yaml
skills-gateway:
  mirror:
    enabled: true
    marketplace: corp-marketplace
    url: https://forge.example.com/mirrors/corp-marketplace.git
    username: ${SGW_MIRROR_USERNAME}
    token: ${SGW_MIRROR_TOKEN}
```

One marketplace, for now. Every key is described in
[Configuration](../reference/configuration.md#read-only-forge-mirror).

!!! warning "The credential is configuration, and never goes in the URL"

    Supply it through `username` and `token`, from your secret store or the
    environment. A URL that embeds a credential is refused at startup, because a
    credential inside a URL ends up in every log line and error message that
    prints one.

The URL's scheme must be on `skills-gateway.allowed-url-schemes` — the same
allowlist that governs which upstreams may be registered. There is one policy for
every address the gateway dereferences, deliberately, so widening it for a mirror
is a decision you make once and see everywhere.

A mirror that is enabled with a missing marketplace, a missing URL, a
non-allowlisted scheme or a credential in the URL **fails startup**. Nothing is
contacted to decide that; only the configuration is read. A mirror that is merely
unreachable never affects startup.

## 3. Fill it

The first recurring reconciliation runs a minute after startup and fills the
mirror from what the marketplace already serves, so a marketplace whose last
approval predates the configuration does not have to wait for another one.

To fill it immediately, ask:

```console
$ curl -X POST localhost:8080/api/mirror/reconcile
```

Administrator-only. It reconciles and answers with the resulting comparison, in
the same shape as the drift report below — so the response tells you whether it
worked rather than only that it was attempted.

## 4. Check for drift

=== "API"

    ```console
    $ curl localhost:8080/api/mirror/drift
    ```

    ```json
    {"enabled":true,"marketplace":"corp-marketplace",
     "url":"https://forge.example.com/mirrors/corp-marketplace.git",
     "reachable":true,"inSync":true,
     "servedTip":"9f2c1d4e6a7b8c9d0e1f2a3b4c5d6e7f80912345",
     "mirrorTip":"9f2c1d4e6a7b8c9d0e1f2a3b4c5d6e7f80912345",
     "missingOnMirror":[],"staleOnMirror":[],"pendingUpdates":0,
     "lastAttemptAt":"2026-09-06T21:14:03Z","lastAttemptOutcome":"ok","error":null}
    ```

Administrator-only. The fields are described in
[Mirror](../reference/api/mirror.md).

Read it as three questions:

| Question | Field |
| --- | --- |
| Could the gateway see the mirror at all? | `reachable` — false means the comparison failed, and `inSync` is then false too, never true by default |
| Does the mirror show what the facade serves? | `inSync`, with `missingOnMirror` and `staleOnMirror` naming the difference |
| Did the last push work? | `lastAttemptOutcome` (`ok`, `failed`, `refused`, `none`) and `error` |

`staleOnMirror` is the field to watch after a revocation: a reference listed
there is content the gateway has stopped serving that the mirror still carries.

### Without asking

The same numbers are published as meters, so a mirror that diverges can raise an
alert instead of waiting to be noticed — see
[Observability](../reference/observability.md#the-read-only-forge-mirror).
`skills_gateway.mirror.stale_refs` above zero for longer than one
`sweep-interval` is the condition worth paging on, and
`skills_gateway.mirror.seconds_since_success` is what tells you whether the count
beside it is current or merely old.

## Fixing drift

Usually: nothing. A reconciliation pushes the whole served reference set rather
than a delta, so whatever the drift was — a push that gave up, an update lost to
a restart, a mirror somebody edited by hand — the next one corrects it, and the
next one is at most `sweep-interval` away.

Do something when the cause is outside the gateway. Fix the code host, the
network or the credential, then either wait for the schedule or reconcile now:

```console
$ curl -X POST localhost:8080/api/mirror/reconcile
```

### When the gateway repairs, it says so

A reconciliation that changes the mirror writes one audit-ledger entry naming
the references it pushed and the references it deleted. Which event it writes is
the useful part:

| Event | Means |
| --- | --- |
| `mirror-updated` | An approval or a revocation reached the mirror. The mirror doing its job. |
| `mirror-drift-repaired` | A reconciliation nothing asked for had to change the mirror. Nothing was approved or revoked, so the mirror had diverged — an earlier push that gave up, or somebody writing to the host directly. |
| `mirror-push-failed` | The push exhausted its retries. The mirror is drifted until the next reconciliation succeeds. |
| `mirror-reconciliation-refused` | The gateway read its own published storage and would not act on what it read. See below. |

A reconciliation that found the mirror already correct writes nothing at all, so
a gateway whose mirror is healthy adds no ledger rows on its timer.

### `mirror-reconciliation-refused`

This one is about the gateway, not the code host, and it is the only outcome that
asks you to look somewhere other than the mirror.

Reconciliation deletes whatever the mirror holds that the served set does not.
That is correct exactly as long as the served set is *true*. If published storage
answers **successfully but incompletely** — a transient backend read failure, a
half-applied migration, a backend pointed at the wrong bucket or prefix — then the
honest conclusion would be "delete everything on the mirror", and on a timer that
is a silent total wipe filed as a repair.

So before contacting the host, the gateway checks the served references it read
against its own record of what that marketplace has approved. When the two
contradict, it pushes nothing, deletes nothing, records
`mirror-reconciliation-refused`, and reports `refused` as `lastAttemptOutcome`
with the reason in `error`. **The mirror is left exactly as it was.**

Treat it as an alert on storage: check the marketplace's published repository and
the storage backend's configuration. A single refusal immediately after an
approval can be the narrow race between the approval record and its publication,
and clears itself on the next pass; a repeated one does not.

!!! note "Drift never means the facade is wrong"

    Everything on this page is about a copy. What the facade serves is decided by
    the approval record and published storage, and no state of the mirror — down,
    stale, empty or tampered with — changes it.
