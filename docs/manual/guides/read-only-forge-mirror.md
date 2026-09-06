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

Whenever a snapshot is approved or revoked, the gateway makes the mirror hold
exactly the references the facade serves for that marketplace — `refs/heads/main`
and `refs/snapshots/*` — and removes anything it still holds in those namespaces
that is no longer served. Nothing else on the mirror is touched: a branch, a tag
or a README the repository already has is left alone.

Two consequences fall out of that shape and are worth stating plainly:

- **Revocation reaches the mirror.** A revoked snapshot's references are gone
  from the mirror as well as from the facade, so nobody can pull the withdrawn
  content out of the copy.
- **A failed update is drift, never a blocked decision.** If the mirror cannot
  be reached, the approval or revocation still happens in full, the facade
  behaves exactly as it would with the mirror off, and the divergence shows up
  in the drift report.

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
  approval gate; content somebody pushes there directly did not, and the gateway
  will remove it at the next reconciliation without announcing that it did.

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

The mirror is brought up to date by the next approval or revocation of that
marketplace. There is no "sync now" in this increment, so a marketplace whose
last approval predates the configuration stays unmirrored — and visibly so —
until its next one. The drift report is what makes that wait observable rather
than a mystery.

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
| Did the last push work? | `lastAttemptOutcome` and `error` |

`staleOnMirror` is the field to watch after a revocation: a reference listed
there is content the gateway has stopped serving that the mirror still carries.

## Fixing drift

There is no repair endpoint in this increment. A reconciliation runs on the next
approval or revocation of the marketplace, and it pushes the whole served
reference set rather than a delta — so whatever the drift was, including a mirror
somebody edited by hand, the next one corrects it.

If the drift is because the mirror has been unreachable, fix the cause and the
same applies. Nothing accumulates that a later reconciliation cannot fix, which
is why a failed push is allowed to simply stop rather than retry forever.

!!! note "Drift never means the facade is wrong"

    Everything on this page is about a copy. What the facade serves is decided by
    the approval record and published storage, and no state of the mirror — down,
    stale, empty or tampered with — changes it.
