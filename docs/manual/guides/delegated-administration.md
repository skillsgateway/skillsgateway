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

## Step 1 — make your grants

Authorization is always enforced, so a grant takes effect the moment it is made.
There is no staging state to make them in first, and no switch to flip
afterwards: the gateway you are configuring is already enforcing. Make the grants
as an administrator — the one your configuration names, which the gateway refused
to start without:

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

- `GET /api/me` reports your effective roles and where each came from.
- A session with no role gets **403** from every mutation and from the ledger,
  while browsing and its own tokens keep working.
- Your approver can approve their marketplace and gets **403** for any other,
  including through bare snapshot ids.

Every grant and revocation lands on the
[audit ledger](../concepts/snapshots-and-ledger.md) as `role-granted` /
`role-revoked` with the acting identity, the target principal, the role, and
the marketplace.

## Grants, or groups?

Grants are not the only source of a role. A session's effective roles are the
union of three:

| Source | `/api/me` reports | Managed by |
| --- | --- | --- |
| `skills-gateway.roles.admins` | `config` | Whoever controls deployment. Unrevocable through the API. |
| A row in the grants API | `grant` | Admins, at runtime, audited on the ledger. |
| An identity-provider claim | `claim` | Your directory, through `skills-gateway.roles.mappings`. |

Prefer **groups** wherever your identity provider already governs membership:
the joiner/mover/leaver process you already have then governs gateway access
too, and there is no second list to keep in step. Reach for a **grant** when
the person is not in a group that fits, when you want the change audited on the
ledger with an acting identity, or when the role must survive the person
leaving the group. Keep at least one **configuration admin** either way — it is
the one source that no directory outage and no bad mapping can take away.

Setting the mappings up, including a worked Microsoft Entra ID walkthrough, is
in [Identity providers](identity-providers.md).

## Locked out anyway?

There is no longer a switch that turns authorization off, so the way back is to
grant the role rather than to stop enforcing it. `skills-gateway.roles.admins` is
the escape hatch that fits in a restart: it is configuration, so no API call is
needed to set it and no API call can revoke it. Add yourself, restart, fix the
grants, and remove yourself again if you would rather not be a standing admin.

For a local checkout, `skills-gateway.dev-insecure-auth=true` skips authentication
entirely and makes its own principal an admin. It refuses to start against a
configured identity provider, so it is not available as a production remedy.

## What this is not

- No portal UI for managing grants or claim mappings yet — the API (and the
  coming CLI) is the management surface for grants; mappings are configuration.
- No roles on personal access tokens: the facade's authorization is
  [token scopes](../reference/api/tokens.md).
- No per-team catalog scoping yet; approver scope is the marketplace.
