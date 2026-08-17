# Delegated administration

Out of the box every authenticated portal session may do everything. This guide
turns that into delegated administration: named **admins**, per-marketplace
**approvers**, and read-only **auditors** — without ever locking yourself out.

## The three roles

| Role | Scope | May |
| --- | --- | --- |
| `admin` | global | Everything, including managing grants. |
| `approver` | one marketplace | Ingest, approve, reject, re-vet, and waive findings for that marketplace only. |
| `auditor` | global | Read the ledger, its export, the webhook subscriber/delivery/sink listings, and retention candidates. Nothing else. |

Roles compose upward — an admin can do everything an approver or auditor can.
Every session, role or not, keeps the browsing surface (marketplaces, the
catalog, snapshot contents, provenance, vetting results, waiver lists) and its
own [access tokens](../reference/api/tokens.md).

Enforcement lives at the REST API, which is what the portal speaks. The
[git facade](../reference/git-facade.md) is untouched: its authorization is
token scopes, a different credential for a different surface.

## Step 1 — stage your grants

Role enforcement is **off by default** (`skills-gateway.roles.enabled=false`):
every check passes and the gateway behaves exactly as before. The grants API
already works in this state, which is how you stage grants *before* anything is
enforced:

```console
$ curl -X POST localhost:8080/api/roles \
    -H 'Content-Type: application/json' \
    -d '{"principal": "alice@example.com", "role": "approver", "marketplace": "acme"}'
$ curl -X POST localhost:8080/api/roles \
    -H 'Content-Type: application/json' \
    -d '{"principal": "carol@example.com", "role": "auditor"}'
```

An approver grant must name a marketplace that exists; admin and auditor grants
must not name one. See the [roles API reference](../reference/api/roles.md) for
the full contract.

!!! note "Inert until enabled"

    While enforcement is disabled, grants are data with no effect — and any
    session can write them. That is deliberate: flipping the switch is a
    configuration decision, made by whoever controls deployment, which is
    strictly more privileged than any API caller.

## Step 2 — name at least one bootstrap admin

```yaml
skills-gateway:
  roles:
    enabled: true
    admins:
      - admin@example.com
```

Principals in `skills-gateway.roles.admins` are admins **by configuration**:
they need no grant row, they appear on their own `/api/me` as a synthetic
`admin` role, and no API call can revoke them. This is the escape hatch that
survives any bad grant edit. Admins granted through the API work exactly as
well — the configuration list is the one you cannot lose.

## Step 3 — enable and verify

Restart with the configuration above. Then verify from a browser session:

- `GET /api/me` now reports `"rolesEnabled": true` and your effective roles.
- A session with no role gets **403** from every mutation and from the ledger,
  while browsing and its own tokens keep working.
- Your approver can approve their marketplace and gets **403** for any other,
  including through bare snapshot ids.

Every grant and revocation lands on the
[audit ledger](../concepts/snapshots-and-ledger.md) as `role-granted` /
`role-revoked` with the acting identity, the target principal, the role, and
the marketplace.

## Locked out anyway?

Set `skills-gateway.roles.enabled=false` and restart: every check passes again,
grants intact. Fix the grants (or the `admins` list) and re-enable.

## What this is not

- No portal UI for managing grants yet — the API (and the coming CLI) is the
  management surface.
- No roles on personal access tokens: the facade's authorization is
  [token scopes](../reference/api/tokens.md).
- No per-team catalog scoping yet; approver scope is the marketplace.
