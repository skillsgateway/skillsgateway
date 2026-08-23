# Declarative estate configuration

Define the whole estate — marketplaces, role grants, webhook subscribers,
audit sinks — in configuration, and let the gateway converge to it. This is
the GitOps deployment shape: the service, its policies **and its estate** live
in one repository; a fresh environment comes up fully formed, with nobody
clicking or curling anything into being.

What reconciliation guarantees:

- **The same trust boundary as the API.** Declared marketplaces pass the exact
  registration gate (`name` rules, the reserved catalog name, the URL scheme
  allowlist); there is no ref key to declare, so the gateway-pinned ref cannot
  be overridden; declared grants pass the exact grant validation.
- **Additive, never destructive.** Removing a line deregisters nothing. There
  is no prune mode: retracting content teams depend on is an explicit human
  action, never a side effect of a deploy.
- **Idempotent.** Only differences are applied. A converged estate reconciles
  with zero writes and zero ledger entries — booting twice writes nothing
  twice.
- **Audited.** Every applied change lands on the append-only ledger under the
  same event name as its API equivalent, attributed to `config-reconciler`, so
  the ledger always distinguishes declarative from interactive changes.
- **Failure-isolated.** A typo in one entry never prevents startup and never
  blocks the other entries; it is loud in the log, on the ledger, and in the
  [report](../reference/api/estate.md).

## 1. Declare the estate

The full key-by-key contract is in
[Configuration](../reference/configuration.md#declarative-estate). A working
deployment declaration:

```yaml
skills-gateway:
  roles:
    enabled: true
    admins:
      - platform-team@example.com
  estate:
    marketplaces:
      - name: corp-marketplace
        url: https://github.com/acme/skills-marketplace.git
        sync-mode: scheduled
    grants:
      - principal: alice@example.com
        role: approver
        marketplace: corp-marketplace
      - principal: audit@example.com
        role: auditor
    webhooks:
      - name: ci-bot
        url: https://ci.example.com/hooks/skills-gateway
        events: snapshot.approved,snapshot.rejected
        secret: ${SGW_ESTATE_CI_BOT_SECRET}
    audit-sinks:
      - name: siem
        url: https://siem.example.com/ingest/skills-gateway
        secret: ${SGW_ESTATE_SIEM_SECRET}
```

Grants may reference marketplaces declared in the same file (marketplaces
reconcile first) **or** marketplaces registered through the API earlier —
adoption can be incremental.

## 2. Supply the secrets

The API generates receiver secrets and shows them once. The declarative form
inverts that: **you** supply the secret, by environment-variable reference
from your secret store, and the gateway treats it as write-only — it never
appears in the log, the ledger, the report, or any API response.

```console
$ export SGW_ESTATE_CI_BOT_SECRET="$(openssl rand -base64 32)"
$ export SGW_ESTATE_SIEM_SECRET="$(openssl rand -base64 32)"
```

Rotation is a config edit: change the referenced value, redeploy (or trigger a
reconcile). The stored secret converges idempotently — the ledger records
`webhook-subscriber-updated` / `changed=secret`, never the value.

!!! warning "Never inline a secret literal"

    The gateway keeps the value out of its own outputs, but it cannot control
    where your configuration lives. A literal in a committed values file is a
    leaked credential. Secrets shorter than 16 characters (including a blank
    from an unset `${VAR:}` default) are refused as reconciliation failures.

## 3. Deploy and verify

Reconciliation runs at startup — after schema migration, before the web
surface serves — so the declaration is in force from the first request. Verify
it from the report and the ledger:

```console
$ curl localhost:8080/api/estate
$ curl localhost:8080/api/audit | jq '.[] | select(.principal == "config-reconciler")'
```

To converge without a restart — after editing the declaration source the
running process reads, or rotating an environment secret:

```console
$ curl -X POST localhost:8080/api/estate/reconcile
```

Under role enforcement the report read is auditor-or-admin and the trigger is
admin-only; the trigger itself is always on the ledger as
`estate-reconcile-triggered`.

## When an entry fails

The gateway starts and serves anyway — a broken declaration must never take a
working estate down — and the entry fails loudly, every run, until it
converges:

- `ERROR` in the application log,
- `estate-reconciliation-failed` on the ledger (with the reason, never a
  secret),
- an `"action": "failed"` entry in [`GET /api/estate`](../reference/api/estate.md).

Two failures are policy, not typos:

- **A declared URL that differs from the registered one.** Marketplace
  upstream URLs are immutable — changing one would swap the supply chain under
  already-approved snapshots, a power the API itself does not have. Register a
  new marketplace instead.
- **`sync-mode: webhook`.** Enabling webhook sync generates the inbound HMAC
  secret and returns it exactly once, which has no declarative form. Set it
  through [`PUT /api/marketplaces/{name}/sync`](../reference/api/marketplaces.md)
  like the [upstream sync guide](upstream-sync.md) describes.

## What stays interactive, and drift

Personal access tokens are user-owned credentials and stay API-only by
design. Deregistration, snapshot approval, waivers — everything that retracts
or publishes content — stays interactive and audited.

Identity-provider role mappings (`skills-gateway.roles.mappings`) are
deliberately **not** an estate object either, for the opposite reason: they are
already pure configuration. No endpoint creates or deletes them, there is no
row to converge, and removing one takes effect on the next request. They sit
next to `skills-gateway.roles.admins` — see
[Identity providers](identity-providers.md). `estate.grants` remains the way to
declare grant *rows*, and it keeps writing them even for a principal who
already holds the same role through a group.

Objects created through the API and absent from the declaration are legitimate
state, not drift to be corrected: reconciliation never touches them. The
ledger's actor column is the drift report — everything not from
`config-reconciler` was someone's interactive decision.
