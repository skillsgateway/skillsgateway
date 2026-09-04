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
[Configuration](../reference/configuration.md#declarative-estate); how the
declaration physically reaches a deployed gateway — the chart's `config` key,
rendered into a mounted ConfigMap — is in
[Deploying on Kubernetes](deploying-on-kubernetes.md#configuring-the-application).
A working deployment declaration:

```yaml
skills-gateway:
  roles:
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

## Declare converge-able state; call for acts and reads

This is where operators guess wrong, so the rule is worth stating as a rule:

- **Estate YAML** is for desired state the gateway re-converges on every boot —
  marketplaces, role grants, webhook subscribers, audit sinks. It is additive,
  idempotent, never prunes, applies the same trust boundary as the API, and, the
  decisive property, needs **no credential in the pipeline at all**. Where it
  covers the object it stays the recommendation, Terraform shops included: a
  provider converging the same objects is a second converger with a secret.
- **The API with a [machine credential](../reference/api/tokens.md#machine-api-credentials)**
  is for what has no declarative form — acts (trigger a reconcile, rebuild the
  catalog, run a re-vet), reads (the ledger, the estate report, adoption,
  drift), one-shot secrets, and objects that are not estate types, such as
  policy rules. It is also the answer where the gateway's configuration is
  genuinely not the deployer's to write, on a managed platform whose values file
  belongs to another team.

**Role grants are estate-only.** No machine credential can call `POST
/api/roles`, whatever scopes it holds, because `estate.grants` already serves
the same need with the same validation and no credential to steal. This is the
sharpest case of the rule above rather than an exception to it.

!!! note "A provider cannot converge a marketplace's whole lifecycle"

    There is no `PUT` or `DELETE /api/marketplaces/{name}` — registration
    exists, deregistration does not — so `terraform destroy` has nothing to
    call. That is a pre-existing gap in the API rather than something machine
    credentials introduce, but a provider author meets it immediately.

## What stays interactive, and drift

Personal access tokens are user-owned credentials and stay API-only by
design, and **machine API credentials are API-only for exactly the same
reason**: a credential's secret has no declarative form, and inverting that —
the operator supplying the secret, as `estate.webhooks` does — would put a
control-plane credential in a values file.

Deregistration, snapshot approval, waivers — everything that retracts
or publishes content — stays interactive and audited, and is unreachable by a
machine credential holding every scope there is.

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
`config-reconciler` was someone's interactive decision, and every entry now
carries an explicit actor type (`human`, `machine` or `system`) so a machine
credential's writes are separable from a person's without parsing names.


## Driving the gateway from CI or Terraform

Where the estate cannot reach — a pipeline that must *trigger* something, read
drift, or run in a shop whose values file belongs to another team — a
[machine API credential](../reference/api/tokens.md#machine-api-credentials) is
the answer.

### Provision one

An administrator does this once, from a browser session, naming only the scopes
the pipeline needs and an expiry:

```bash
curl -X POST https://gateway.example.com/api/tokens/machine \
  -H 'Content-Type: application/json' \
  --data '{
    "principal": "platform-ci",
    "name": "estate-pipeline",
    "apiScopes": ["estate:read", "estate:reconcile", "marketplaces:register"],
    "expiresAt": "2026-11-26T00:00:00Z"
  }'
```

The response carries the cleartext exactly once. Put it in the pipeline's secret
store; the gateway keeps only a hash.

### Use it

```yaml title=".github/workflows/estate.yml (excerpt)"
- name: Reconcile the declared estate
  env:
    SKILLS_GATEWAY_TOKEN: ${{ secrets.SKILLS_GATEWAY_TOKEN }}
  run: |
    curl -sSf -X POST "$GATEWAY/api/estate/reconcile" \
      -H "Authorization: Bearer $SKILLS_GATEWAY_TOKEN"

- name: Fail the build on unreconciled entries
  run: |
    curl -sSf "$GATEWAY/api/estate" \
      -H "Authorization: Bearer $SKILLS_GATEWAY_TOKEN" \
      | jq -e '.entries | map(select(.outcome == "failed")) | length == 0'
```

A Terraform provider follows the same shape: the credential in the provider
block, `POST /api/marketplaces` to register, `GET /api/estate` to read back.

### What this credential cannot do, by design

State it plainly, because a pipeline author will otherwise try:

- **It cannot approve or reject a snapshot**, and cannot create or delete a
  waiver. Publishing content is a human decision, and no scope reaches it.
- **It cannot retract content** — no snapshot delete, no restore, no retention
  evaluate or compact. It can read `GET /api/retention/candidates` and stop
  there.
- **It cannot grant a role**, to anyone, including itself. Declare grants in
  `estate.grants` instead; `roles:read` lets the pipeline *detect* a grant made
  by hand, which the estate cannot discover on its own.
- **It cannot mint another credential**, including a replacement for itself, so
  a compromised one cannot outrun its own revocation.
- **It cannot clone a marketplace.** A machine credential reaches no repository
  through the git facade, including the ones an empty fetch scope would grant
  any other token. If the pipeline also needs content, that is a separate
  personal access token.

- **It must not send a cookie.** A request carrying both a bearer credential and
  a `Cookie` header is refused with a bare 401 — including a session-affinity
  cookie injected by a load balancer.

### Rotate it

```bash
curl -X POST "$GATEWAY/api/tokens/machine/$ID/rotate"
```

The same principal, name, expiry deadline and every one of the same scopes, with
a new secret; the old one is dead before the new one is returned. The expiry is
a **deadline**, not a duration, so rotation does not extend the credential's
life — that is what makes the cap meaningful.
