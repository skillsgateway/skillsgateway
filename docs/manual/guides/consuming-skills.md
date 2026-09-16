# Consuming approved skills

The facade is an ordinary read-only git remote, so clients need no
modification — only a credential and a URL.

!!! tip "The wizard does steps 1–4 for you"

    A marketplace's detail page in the portal leads with **Set up a client**,
    which composes the exact commands below for that marketplace — token
    creation (show-once, as always), the credential line, the `claude plugin
    marketplace add` command, and the clone URL. The rest of this guide is the
    same procedure by hand. If the page says the marketplace is **not being
    served yet**, the commands are correct but will be answered `404` until a
    snapshot is approved. See
    [the portal reference](../reference/portal.md#set-up-a-client).

## 1. Get a credential

Git clients authenticate with a token, not with your portal session cookie.
There are two kinds, and for a human at a keyboard the first is the better
default.

If your gateway has
[identity-provider bearer tokens](#no-credential-at-all-sso-in-the-credential-helper)
switched on, there is a third option in which you mint nothing at all — skip to
it.

### A session credential (recommended for people)

If you are already logged in to the portal, mint a credential from that session
instead of creating a standing token:

```console
$ curl -X POST localhost:8080/api/tokens/session \
    -H 'Content-Type: application/json' -d '{"name":"my-laptop"}'
```

```json
{"id":1,"name":"my-laptop","token":"sgw_...","expiresAt":"2026-08-23T17:00:00Z",
 "sessionDerived":true,"pushScopes":[]}
```

The gateway sets the lifetime —
[`skills-gateway.tokens.session-ttl`](../reference/configuration.md#access-tokens),
8 hours by default — and you cannot ask for longer. That is the entire
difference between this and a personal access token: a credential whose life
the holder chooses is a standing credential wearing a different name. It also
carries no publication authority, and it is marked `sessionDerived` wherever it
appears, so the [audit ledger](../concepts/snapshots-and-ledger.md) distinguishes
"a fetch by a credential minted moments after an SSO login" from "a fetch by a
token provisioned months ago".

!!! warning "The timer is the control, not your session"

    A session credential is **not** revoked when you log out or close the
    browser — the gateway does not track session lifetime. It dies when its
    expiry passes, and until then it is a bearer token like any other. What it
    buys is a bounded window and an attributable origin, not immunity.

### A personal access token (for CI, and anything without a browser)

Git clients authenticate with PATs, not with your portal session.

=== "Portal"

    Open the user menu (top right) → **Your tokens** → enter a name → **Create
    token**. The value appears in a dialog with a copy button.

=== "API"

    ```console
    $ curl -X POST localhost:8080/api/tokens \
        -H 'Content-Type: application/json' -d '{"name":"my-laptop"}'
    ```

    ```json
    {"id":1,"name":"my-laptop","token":"sgw_...","createdAt":"..."}
    ```

!!! danger "Shown exactly once"

    Only a SHA-256 digest is stored. A lost token cannot be recovered — revoke
    it and create another.

Tokens are scoped to the creating principal: you only ever see and revoke your
own.

!!! tip "Scope it, expire it, rotate it"

    A token can be limited to named marketplaces (`"scopes":["acme","catalog"]`)
    and given an expiry (`"expiresAt":"..."`) — a leaked CI token limited to one
    marketplace is an incident contained to that marketplace, and one that dies
    on its own bounds the window a leak matters. Suspect exposure without
    wanting to reconfigure anything? `POST /api/tokens/{id}/rotate` issues a
    fresh secret with the identical grant and kills the old one in the same
    act. See [Access tokens](../reference/api/tokens.md).

### No credential at all: SSO in the credential helper

If the gateway sets
[`skills-gateway.facade.idp-bearer.enabled`](../reference/configuration.md#identity-provider-bearer-tokens-on-the-facade),
a git client that speaks generic OAuth can authenticate straight against your
organisation's SSO. Nothing is minted, nothing is stored on the gateway, and the
fetch is still attributed to you — the gateway takes your identity from the same
claim the portal does.

[Git Credential Manager](https://github.com/git-ecosystem/git-credential-manager)
is configured entirely through `git config`. Four settings, all keyed on the
gateway's URL:

```console
$ git config --global credential.https://skills.corp.example.oauthClientId corp-skills-gateway
$ git config --global credential.https://skills.corp.example.oauthAuthorizeEndpoint https://idp.corp.example/oauth2/v2.0/authorize
$ git config --global credential.https://skills.corp.example.oauthTokenEndpoint https://idp.corp.example/oauth2/v2.0/token
$ git config --global credential.https://skills.corp.example.oauthScopes openid
```

Then clone with no credential in the URL at all:

```console
$ git clone https://skills.corp.example/git/acme
```

The first request is answered `401`, the helper opens a browser, you complete
the usual SSO, and the token it receives is sent as
`Authorization: Bearer …` on the retry.

=== "Worked example (the mock provider the e2e suite runs)"

    The repository's end-to-end suite runs a mock OIDC provider on
    `localhost:9090` and the gateway on `localhost:8081`. Against that pair, with
    the gateway started with `SKILLSGATEWAY_FACADE_IDPBEARER_ENABLED=true` and
    `SKILLSGATEWAY_OIDC_ISSUER=http://localhost:9090/default`:

    ```console
    $ git config --global credential.http://localhost:8081.oauthClientId e2e-client
    $ git config --global credential.http://localhost:8081.oauthAuthorizeEndpoint http://localhost:9090/default/authorize
    $ git config --global credential.http://localhost:8081.oauthTokenEndpoint http://localhost:9090/default/token
    $ git config --global credential.http://localhost:8081.oauthScopes openid
    $ git clone http://localhost:8081/git/acme
    ```

    The values are real — they are the ones
    [`e2e/run-e2e.sh`](https://github.com/skillsgateway/skillsgateway/blob/main/src/main/frontend/e2e/run-e2e.sh)
    exports — but the **credential-helper half of this is not automated**: the
    suite drives a browser, not a credential manager. What the suite does verify
    end to end is the gateway's half, with a real `git clone` carrying a real
    bearer token in a header.

=== "Without a credential helper"

    Any client that can set a header works, which is also how to test the
    gateway's side directly:

    ```console
    $ git -c http.extraHeader="Authorization: Bearer ${SGW_ACCESS_TOKEN}" \
        clone https://skills.corp.example/git/acme
    ```

    Do not put this in a shell history or a CI log — it is a bearer credential
    like any other.

!!! warning "Short-lived, and the gateway cannot revoke it"

    A bearer token dies when the identity provider says so, usually within the
    hour, and that is the whole control: the gateway has no kill switch for one.
    If you need a credential you can revoke centrally — a CI pipeline's, most of
    all — use a personal access token. That is why both exist.

!!! note "Your marketplace scopes"

    A bearer token has no scope list, so it reaches every marketplace the gateway
    serves — the same as a personal access token created without `scopes`. If you
    need a credential limited to one marketplace, create a scoped token.

## 2. Verify the remote

The username is ignored; the token goes in the password field.

```console
$ git ls-remote https://token:sgw_...@skills.corp.example/git/acme
3f9c2ab...	refs/heads/main
```

A 404 here means the marketplace has never had a snapshot approved — there is
nothing to serve. A 401 means the token is wrong, expired or revoked. The portal
says the same thing on the marketplace's own page when nothing is being served
yet, so the two are not confused for each other.

## 3. Store the credential

So the client never prompts:

```console
$ printf 'protocol=https\nhost=skills.corp.example\nusername=token\npassword=sgw_...\n' \
    | git credential approve
```

Or configure the username and let your credential helper hold the secret:

```console
$ git config --global credential.https://skills.corp.example.username token
```

## 4. Point your agent at it

!!! tip "One URL for everything"

    Instead of adding each marketplace separately, add the
    [virtual catalog](virtual-catalog.md) — `/git/catalog` — which aggregates
    the currently approved snapshot of every governed marketplace, with plugin
    names prefixed by their marketplace.

=== "Claude Code"

    ```console
    $ claude plugin marketplace add https://skills.corp.example/git/acme
    $ claude plugin install acme-tools
    ```

=== "Copilot / Cursor"

    Point the tool at the same URL as an ordinary skills repository — the
    facade serves plain git, and the open Agent Skills format needs nothing
    more.

=== "CI"

    ```console
    $ git clone --depth 1 https://token:${SGW_TOKEN}@skills.corp.example/git/acme
    ```

    Issue a dedicated token per pipeline so revocation is surgical.

## What clients see

Exactly one branch, `main`, at the approved SHA. Unapproved snapshots do not
exist on this remote, and pushing here is impossible — receive-pack is disabled
by construction on the facade, so `git push` gets a "service not enabled"
rejection. (Publishing to a marketplace the gateway hosts is a different
endpoint and a different credential; see
[Publishing first-party skills](publishing-first-party-skills.md).)

The served SHA changes only when a reviewer approves a new snapshot. Upstream
movement alone never changes what you receive.

## Every fetch is recorded

Each `git fetch` appends entries to the audit ledger with your principal, the
marketplace, the ref and the SHA. Check the portal's **Audit log** page.

This is what makes "which identities ever received this exact content"
answerable — see [Snapshots and the audit ledger](../concepts/snapshots-and-ledger.md).

## Making the gateway the only door

Governance that relies on developers choosing the right URL is documentation,
not control. Two mechanisms carry the load:

**Fleet-managed client settings.** Claude Code's managed settings support
`strictKnownMarketplaces` (managed-only), which restricts users to an explicit
marketplace allowlist. Combined with `extraKnownMarketplaces` and
`enabledPlugins` you can pre-register the gateway and force-install the approved
set. Distribute via MDM. Copilot and Cursor have no equivalent hard switch
today.

**Network egress policy.** Block — or at minimum alert on — direct access from
developer machines and CI to upstream marketplace hosts. Blocked attempts are
themselves a useful signal.

**And the carrot.** The gateway is faster (LAN-local), simpler (one URL, a
pre-approved catalog, no security tickets) and works in restricted networks.
Making the paved road genuinely better is half of enforcement.
