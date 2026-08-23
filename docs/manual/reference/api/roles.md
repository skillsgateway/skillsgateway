# Roles

Role grants for delegated administration. Concepts and the enable/bootstrap
walkthrough live in
[Delegated administration](../../guides/delegated-administration.md); the
enforcement matrix is on each endpoint's own reference page.

While `skills-gateway.roles.enabled=false` (the default) these endpoints are
open to any authenticated session and the grants they manage have no effect —
staging data for the flip. Once enabled, all three are **admin-only**.

---

## `GET /api/roles`

List every current grant.

```console
$ curl localhost:8080/api/roles
```

```json
[{"id":1,"principal":"alice@example.com","role":"approver","marketplace":"acme",
  "grantedBy":"admin@example.com","grantedAt":"2026-08-17T09:00:00Z"},
 {"id":2,"principal":"carol@example.com","role":"auditor","marketplace":null,
  "grantedBy":"admin@example.com","grantedAt":"2026-08-17T09:01:00Z"}]
```

Configuration-bootstrapped admins and roles derived from identity-provider
claims are not grants and do not appear here; they show as effective roles with
source `config` and `claim` on [`/api/me`](index.md#session).

| Status | Meaning |
| --- | --- |
| 200 | The current grants. |
| 403 | Enforcement is enabled and the caller is not an admin. |

---

## `POST /api/roles`

Grant a role.

```console
$ curl -X POST localhost:8080/api/roles \
    -H 'Content-Type: application/json' \
    -d '{"principal": "alice@example.com", "role": "approver", "marketplace": "acme"}'
```

| Field | Required | Notes |
| --- | --- | --- |
| `principal` | yes | The identity as the OIDC session reports it. |
| `role` | yes | `admin`, `approver`, or `auditor`. |
| `marketplace` | for `approver` | Must exist at grant time. Forbidden for `admin` and `auditor`. |

| Status | Meaning |
| --- | --- |
| 201 | Granted; the grant is on the ledger as `role-granted`. |
| 403 | Enforcement is enabled and the caller is not an admin. |
| 404 | An approver grant named a marketplace that does not exist. |
| 409 | The identical grant already exists. |
| 422 | Missing principal, unknown role, approver without a marketplace, or a global role with one. |

---

## `DELETE /api/roles/{id}`

Revoke a grant. The row is deleted; the ledger keeps the history as
`role-revoked`.

| Status | Meaning |
| --- | --- |
| 204 | Revoked. |
| 403 | Enforcement is enabled and the caller is not an admin. |
| 404 | No such grant. |

!!! warning "Configuration admins cannot be revoked here"

    Principals in `skills-gateway.roles.admins` have no grant row — there is
    nothing this endpoint could delete. Removing one is a configuration change,
    by design: the bootstrap list is the escape hatch that survives a bad
    grant edit.
