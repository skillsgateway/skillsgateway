# Consuming approved skills

The facade is an ordinary read-only git remote, so clients need no
modification — only a credential and a URL.

## 1. Create a personal access token

Git clients authenticate with PATs, not with your portal session.

=== "Portal"

    **Access tokens** → enter a name → **Create token**. The value appears in a
    dialog with a copy button.

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

## 2. Verify the remote

The username is ignored; the token goes in the password field.

```console
$ git ls-remote https://token:sgw_...@skills.corp.example/git/acme
3f9c2ab...	refs/heads/main
```

A 404 here means the marketplace has never had a snapshot approved — there is
nothing to serve. A 401 means the token is wrong or revoked.

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
exist on this remote, and pushing is impossible — receive-pack is disabled by
construction, so `git push` gets a "service not enabled" rejection.

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
