# Identity providers

The gateway's web surface is OIDC-only ([ADR 0002](../reference/decisions.md)),
and it is its own BFF: the browser holds a session cookie, never a token. This
guide connects it to an enterprise identity provider and — the part that
removes most of the day-to-day administration — turns the provider's **groups**
into gateway roles, so nobody has to keep grant rows in step with who joined
which team.

## What the gateway needs from any provider

| Setting | Environment variable | What it is |
| --- | --- | --- |
| Client id | `SGW_OIDC_CLIENT_ID` | The application registration's id. |
| Client secret | `SGW_OIDC_CLIENT_SECRET` | Injected as a secret; never in configuration files. |
| Authorization endpoint | `SGW_OIDC_AUTHORIZATION_URI` | Where the browser is sent to log in. |
| Token endpoint | `SGW_OIDC_TOKEN_URI` | Where the gateway redeems the code. |
| JWKS endpoint | `SGW_OIDC_JWK_SET_URI` | Keys the ID token is verified with. |
| Principal claim | `SGW_OIDC_USER_NAME_ATTRIBUTE` | Which claim names the user. Default `sub`. |
| Scopes | `SGW_OIDC_SCOPE` | Default `openid`. Widen if a scope is needed for group claims. |
| Expected issuer | `SKILLSGATEWAY_OIDC_ISSUER` | The `iss` every ID token must carry. |

The redirect URI to register is:

```
https://<your-gateway-host>/login/oauth2/code/idp
```

!!! warning "Pin the issuer"

    The gateway configures its endpoints explicitly rather than by issuer
    discovery, and Spring Security compares an ID token's `iss` only when an
    issuer is configured. Leave `skills-gateway.oidc.issuer` unset and nothing
    checks it — which, on an authorization endpoint that serves many tenants,
    means a token minted for another organisation verifies against the same
    keys and looks exactly like yours. The gateway warns at startup while this
    is unset.

## Groups instead of grants

Role enforcement (see [Delegated administration](delegated-administration.md))
knows three roles and, by default, two sources of them: the
`skills-gateway.roles.admins` list and rows in the grants API. A third source
is the provider's own claims:

```yaml
skills-gateway:
  roles:
    enabled: true
    admins:
      - break-glass@example.com     # keep this; see below
    claim: groups
    mappings:
      - claim-value: 8f1c0a2e-0000-0000-0000-000000000000
        role: admin
      - claim-value: gateway-approvers-acme
        role: approver
        marketplace: acme
      - claim-value: security-auditors
        role: auditor
```

A session's effective roles are the **union** of all three sources. Everything
else about the role model is unchanged: enforcement is still deny-by-default
once enabled, an approver is still confined to one marketplace including
through bare snapshot and waiver ids, and an auditor still cannot mutate
anything.

Three properties of the matching are worth knowing before you write mappings:

- **`claim-value` is the provider's string, not a gateway role name.** On an
  app registration shared with other services the group ids and app-role values
  belong to the organisation, so there is no convention to lean on — you state
  the mapping.
- **The match is exact**, after trimming surrounding whitespace. No prefixes,
  no globs, no case folding: a looser match could only ever widen who is
  privileged.
- **A malformed mapping refuses startup.** An unknown role, a blank value, an
  `approver` with no marketplace or an `admin`/`auditor` with one — the
  application does not come up. A typo that quietly grants nothing is exactly
  the failure this avoids. Naming a marketplace that does not exist *yet* is
  fine: the mapping matches nothing until it is registered.

`GET /api/me` reports the source of every effective role — `config`, `grant` or
`claim` — so "why does this person have admin" is answerable from the session
endpoint.

!!! tip "Keep one configuration admin"

    `skills-gateway.roles.admins` needs no group, no token and no directory. It
    is what gets you back in when a group is renamed, a claim stops being
    emitted, or a mapping is wrong.

### Dry-run before you enforce

Configure the mappings while `skills-gateway.roles.enabled` is still `false`.
Nothing is enforced, but `/api/me` already reports what each session *would*
hold — so you can confirm the claim arrives and the values match before the
switch flips.

### Nested claims

Some providers nest membership. A dotted `claim` walks the path:

```yaml
skills-gateway:
  roles:
    claim: realm_access.roles
```

The claim may be a list of strings or a single string. A delimited string is
treated as **one** value and is never split — inventing a delimiter would be
guessing at what the provider meant.

### When the group claim goes missing

Providers cap how many groups a token may carry. Past the cap, some drop the
claim entirely and set a marker instead — leaving a user who can see their own
membership wondering why the gateway refuses them.

The gateway detects that: `GET /api/me` reports `claimsTruncated: true`, and the
startup log says so too. It is deliberately distinguished from a session that
simply has no memberships. The fix is on the provider's side — emit application
roles instead of raw groups, or restrict the claim to the groups assigned to
this application — not on the gateway's.

### What claims never do

Claims are read only from a browser session established through the identity
provider. A [personal access token](../reference/api/tokens.md) on the git
facade, the `dev-insecure-auth` principal, and the anonymous inbound webhook
carry no claims and derive no role, whatever authorities they hold. The
facade's authorization remains token scopes; that is a different credential for
a different surface.

## Microsoft Entra ID

A worked example. Substitute your tenant id for `<tenant>`.

### 1. The app registration

If your platform already has **one shared registration** for several services,
you are adding to it, not creating one:

1. Add the redirect URI `https://<gateway-host>/login/oauth2/code/idp` under
   **Web** — logins fail until this exists.
2. Mint a client secret scoped to this service. Put its value in your secret
   store and inject it as `SGW_OIDC_CLIENT_SECRET`; it never belongs in a
   values file or a repository.
3. Under **Token configuration**, add the **groups claim** — or, better, define
   **app roles** and assign groups to them, which is what avoids the overage
   problem below.

### 2. Endpoints

```yaml
SGW_OIDC_CLIENT_ID: <application (client) id>
SGW_OIDC_CLIENT_SECRET: <from your secret store>
SGW_OIDC_AUTHORIZATION_URI: https://login.microsoftonline.com/<tenant>/oauth2/v2.0/authorize
SGW_OIDC_TOKEN_URI: https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token
SGW_OIDC_JWK_SET_URI: https://login.microsoftonline.com/<tenant>/discovery/v2.0/keys
SGW_OIDC_USER_NAME_ATTRIBUTE: preferred_username
SGW_OIDC_SCOPE: openid,profile,email
SKILLSGATEWAY_OIDC_ISSUER: https://login.microsoftonline.com/<tenant>/v2.0
```

The issuer is the tenant boundary here: the multi-tenant endpoints sign with
keys from a shared JWKS, so without it a token from another tenant verifies.

### 3. The principal claim

`sub` on a Microsoft Entra ID token is a **pairwise** identifier — unique per
user *per application* and meaningless to a human. Left at the default, every
grant, every `roles.admins` entry and every audit-ledger row would name an
opaque string. `preferred_username` is the readable choice, which is why the
scope set above includes `profile`.

Its cost, stated plainly: `preferred_username` is **mutable**. If someone's
sign-in name changes, grant rows and `roles.admins` entries keyed on the old
value stop matching, silently. That is an argument for mapping groups rather
than granting principals — group membership follows the person, not the
string. Where you need an identifier that never changes, `oid` is immutable and
unreadable; pick which cost you would rather pay, and be consistent.

### 4. Which claim to map

- **App roles** (recommended) arrive in the `roles` claim, carry the values you
  defined, and have no overage problem:

  ```yaml
  skills-gateway:
    roles:
      claim: roles
      mappings:
        - claim-value: SkillsGateway.Admin
          role: admin
  ```

- **Groups** arrive in the `groups` claim as **object ids**, not display names —
  copy the object id from the group's overview page. Above roughly 200
  memberships the claim is dropped and `hasgroups: true` appears instead; the
  gateway reports this as `claimsTruncated` on `/api/me`. Restricting the groups
  claim to "groups assigned to the application" avoids it in most tenants; app
  roles avoid it entirely.

### 5. Verify

```console
$ curl -s -b session.txt https://<gateway-host>/api/me | jq
{
  "username": "alice@example.com",
  "rolesEnabled": true,
  "roles": [{"role": "admin", "marketplace": null, "source": "claim"}],
  "claimsTruncated": false
}
```

`source: claim` is the confirmation that the mapping — not a grant row — is
what made this session an admin.

## Locked out?

Set `skills-gateway.roles.enabled=false` and restart: every check passes again
and nothing is lost. Fix the mappings or the `admins` list, then re-enable.
