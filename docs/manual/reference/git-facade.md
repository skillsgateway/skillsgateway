# Git smart-HTTP facade

The facade is the only surface end users ever touch. It is a JGit `GitServlet`
registered at `/git/*`, with its own stateless security chain ordered ahead of
the web chain.

Clients need no modification: it is an ordinary read-only git remote.

## URLs

```
https://skills.corp.example/git/{marketplace}
https://skills.corp.example/git/{marketplace}.git
```

The standard smart-HTTP endpoints below that prefix:

- `GET /git/{marketplace}/info/refs?service=git-upload-pack`
- `POST /git/{marketplace}/git-upload-pack`

`{marketplace}` is validated against `^[a-z0-9][a-z0-9_-]{0,62}$` (at most 63
characters) after stripping an optional `.git` suffix. Anything else is a 404 —
which is also what blocks path traversal into arbitrary directories.

## Authentication

HTTP Basic, backed by the PAT provider. An OIDC browser **session** can never
authenticate a git fetch: no session is read and no cookie is honoured on this
chain. A second, opt-in credential kind is described under
[Identity-provider bearer tokens](#identity-provider-bearer-tokens) below; it is
off unless an operator turned it on, and it does not change anything in this
section.

**Only the password field is read.** The username is ignored; `token` is the
convention. This is what makes the standard git credential helper work
unmodified.

```console
$ git ls-remote https://token:sgw_...@skills.corp.example/git/acme
3f9c2ab...	refs/heads/main
```

```mermaid
sequenceDiagram
    participant G as git client
    participant F as gitChain (Order 1)
    participant PAT as PatAuthenticationProvider
    participant S as Git storage
    participant L as fetch_log

    G->>F: GET /git/acme/info/refs?service=git-upload-pack
    F-->>G: 401 + WWW-Authenticate Basic
    G->>F: retry with Basic token:sgw_...
    F->>PAT: authenticate (password only)
    PAT->>PAT: sha256Hex(token) → find active token
    alt no active token
        PAT-->>G: 401 bad credentials
    else valid
        PAT-->>F: principal + ROLE_GIT
        F->>S: publishedIfServing("acme")
        alt never approved
            S-->>G: 404 repository not found
        else serving
            S-->>F: published repo (read-only)
            F->>L: record info-refs
            F-->>G: advertise refs/heads/main
            G->>F: POST /git/acme/git-upload-pack
            F->>L: record upload-pack per wanted object
            F-->>G: packfile
        end
    end
```

An unauthenticated request gets **401** with `WWW-Authenticate: Basic`, which is
exactly what the credential helper expects; a bad or revoked token gets 401 too.

!!! note "The facade chain is unconditional"

    `skills-gateway.dev-insecure-auth=true` opens the web surface but does not
    touch `/git/**`. A valid credential is still required.

## Identity-provider bearer tokens

**Off by default**
([`skills-gateway.facade.idp-bearer.enabled`](configuration.md#identity-provider-bearer-tokens-on-the-facade)).
While off, nothing described here exists — no filter, no provider, no decoder.

When on, `/git/**` additionally accepts `Authorization: Bearer <jwt>` where the
token was issued by the same identity provider the portal authenticates against.
Both the OIDC access token and the ID token are accepted; providers disagree about
which one a generic OAuth client receives, and the checks that matter are the same
for both.

| Check | Source |
| --- | --- |
| Signature | The `idp` registration's `jwk-set-uri`, cached and refreshed on an unknown `kid` |
| `iss` | `skills-gateway.oidc.issuer`, which this capability makes mandatory |
| `aud` | `skills-gateway.facade.idp-bearer.audience`, defaulting to the OAuth2 client id; must *contain* it, and a token with no `aud` is refused |
| `exp` / `nbf` | The current time, with 60 seconds of clock leeway |

The principal is the claim named by
`spring.security.oauth2.client.provider.idp.user-name-attribute` (`sub` by
default) — the same claim a browser session's identity comes from. The same
person fetching with an SSO token and with a portal-minted PAT therefore writes
the same `principal` on the ledger, and the
[adoption report](api/adoption.md) cannot tell them apart.

**Authorization is unchanged.** A bearer token carries no marketplace scope list,
so it permits every marketplace the gateway serves — exactly what an unscoped PAT
permits. Roles are not consulted here for any credential kind.

**The challenge is still `Basic`.** An unauthenticated request, and a request
whose bearer token is refused for any reason, both get the ordinary 401 with
`WWW-Authenticate: Basic` — so a client holding an expired SSO token falls back
to its credential helper instead of failing outright.

!!! warning "The gateway cannot revoke a bearer token"

    Revocation is the identity provider's; the gateway's only lever is the
    token's own expiry, which is typically minutes to an hour. That short life is
    the control, and it is a genuinely different trade from a PAT: much smaller
    blast radius, no gateway-side kill switch. Deployments that need one keep
    using PATs.

A token accepted here reaches **nothing** on `/api/**`: the administrative
interface authenticates only a gateway-issued machine credential, and the rest of
the web surface only an interactive session.

## What is served

Two namespaces, from `{data-dir}/published/{marketplace}.git`, and nothing else:

| Advertised | What it is |
| --- | --- |
| `refs/heads/main` | The served tip. This is what a clone checks out. |
| `refs/snapshots/<sha>` | Every approved snapshot, fetchable by name in its own right — including one a later approval has superseded, until it is revoked. |

`HEAD` is advertised too, because a clone reads it to learn which branch to check
out.

Only a registered marketplace is served. A [removed](api/marketplaces.md#delete-marketplacesname)
marketplace answers as an unknown one does, whatever its published repository
still holds.

This is an **allowlist**, not a description of what the repositories happen to
contain. Upload-pack advertises every ref it can see unless told otherwise, and
every advertised tip is a legal `want`, so the facade states its surface
explicitly: references the gateway keeps for its own purposes — the catalog's
rebuild scaffolding, the staging namespace publication uses before it commits to
serving anything — are never on the wire, whether or not the code that tidies
them up succeeded.

Repository resolution opens only the published path and returns nothing unless
`refs/heads/main` resolves. A marketplace that has been registered and ingested
but never approved therefore returns **404**: there is nothing to serve, and the
quarantine repository is not reachable from here by any code path.

The served SHA changes only when a reviewer approves a snapshot.

!!! note "Revocation removes both refs"

    Because a snapshot is fetchable by name as well as through `main`, taking one
    off the wire means removing both references. Revoking a snapshot that a later
    approval has already superseded removes only its own pinned reference and
    leaves the marketplace serving.

## The facade accepts no writes

Receive-pack is disabled by construction — the servlet is configured with a null
receive-pack factory, so no `ReceivePack` can be created at all. `git push` to
`/git/**` receives the standard "service not enabled" rejection, whatever the
credential.

There is no write-side endpoint, filter or hook **on this servlet** to
misconfigure. This is a structural guarantee, not a policy one.

The gateway does accept a push, for marketplaces it
[hosts itself](../guides/publishing-first-party-skills.md) — on `/publish/**`,
which is a different servlet resolving a different repository under a different
token scope, and which cannot reach a published repository any more than this
one can construct a `ReceivePack`. See
[ADR 0007 — First-party hosting and the publish endpoint](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0007-first-party-hosting-and-the-publish-endpoint.md) for why the two are separate objects rather than one
with a mode flag.

## Auditing

Two hooks append to the ledger:

| Event | When | `ref` |
| --- | --- | --- |
| `info-refs` | Ref advertisement, recording the resolved `main` SHA. | `refs/heads/main` — an advertisement is about the tip. |
| `upload-pack` | One entry per wanted object when the packfile is served. | The advertised ref that object resolves to. |

Each entry carries the client address as `source`, the PAT's principal, the
marketplace, the ref and the SHA. Negotiation rounds are not recorded.

Because both namespaces above are legal wants, an `upload-pack` entry names
which one the client received content through. A want that is not the tip can
only have come from a `refs/snapshots/<sha>` advertisement and records that ref,
so a fetch of a superseded snapshot is distinguishable in the ledger from a clone.

A want **equal** to the tip records `refs/heads/main`. While a snapshot is
current, `refs/heads/main` and its `refs/snapshots/<sha>` are the same commit and
the smart protocol carries only object ids in a want, so a clone and a fetch by
name are not separable here; the tip is the recorded answer. The `sha` column
still pins the delivered content exactly, which is what
[adoption and staleness](api/adoption.md) aggregate on — they never read `ref`.

The `upload-pack` entry is appended when the send **begins**, so a transfer the
client abandons is still recorded. The ledger names every identity that may hold
the content, not every identity that provably received it.

The append is synchronous and unguarded: a fetch fails outright if the ledger
cannot be written. Serving is deliberately no more available than the record of
it — see
[Appending is on the serving path](../concepts/snapshots-and-ledger.md#appending-is-on-the-serving-path).

See [Audit](api/audit.md#events) for the full entry contract, including what
entries written before this behaviour shipped record.

## Checking content you already hold

Withdrawing a snapshot removes it from the served refs; it does not reach a
machine that cloned it earlier. Removing a marketplace withdraws each of its
approved snapshots the same way, so a holder asks the same question and is told
`revoked` — unless the name has since been registered again and the new
marketplace has approved the same commit. `POST /status/v1/snapshots` is what such a machine
asks.

It is not under `/git/`, because that prefix is a JGit servlet mapping and no
JSON endpoint is reachable beneath it. It is not under `/api/v1/` either, because
that surface accepts only an OIDC session or a gateway-issued machine
credential, and a git client holds neither. It has its own stateless chain
accepting exactly the credentials described under
[Authentication](#authentication) above — a PAT over Basic, and an
identity-provider bearer token where that is enabled. No session is read, no
cookie is honoured, and none is set.

### Request

```json
{
  "holdings": [
    {"marketplace": "platform-skills", "sha": "9f2c1b8e…"}
  ]
}
```

| Field | Contract |
| --- | --- |
| `marketplace` | The name as it appears in the fetch URL, validated against `^[a-z0-9][a-z0-9_-]{0,62}$`. |
| `sha` | A full 40-character commit id. An abbreviation is refused. |
| `holdings` | At most **256** pairs per request. |

### Response

One result per requested pair, in the order asked, echoing the pair.

| Field | Contract |
| --- | --- |
| `state` | `approved`, `revoked`, or `unknown`. |
| `revokedAt` | When the content was withdrawn. `null` unless the state is `revoked`. |
| `approvedOverReversedRevocation` | `true` when this approval stands over a withdrawal that was later [reversed](../guides/approving-snapshots.md#putting-it-back-takes-a-second-administrator), so content served over a reversed withdrawal is never indistinguishable from content nobody ever withdrew. |

`approved` ignores supersession on purpose. A later approval does not retract an
earlier one — `refs/snapshots/<sha>` stays advertised — and the question a
holder is asking is whether it may keep using what it has, not whether it is
current. Staleness is [adoption's](api/adoption.md) question.

!!! warning "`unknown` is the absence of a statement, never a pass"

    It is the answer for a commit the gateway never held, one whose record has
    aged out, one that was ingested but never approved, a marketplace outside a
    [scoped token's](api/tokens.md) list, and a marketplace that does not
    exist. Those are deliberately indistinguishable: the same rule that stops a
    scoped token enumerating marketplaces through the facade applies here, so
    the check cannot double as a directory of the estate. A client must treat
    `unknown` as not approved.

**The withdrawal reason is not in the answer.** A reason is mandatory on a
withdrawal and routinely names an undisclosed vulnerability or a compromised
maintainer. An identity entitled to it reads it from the
[ledger](api/audit.md), which is an auditor's read.

### Refusals

| Status | Cause |
| --- | --- |
| 400 | More than 256 pairs, a malformed marketplace name, or a `sha` that is not a full commit id. The request is refused whole rather than partly answered — a partial answer that looks complete is the failure a holder cannot detect. |
| 401 | No credential, or one the facade does not accept. |

### It is not a fetch

Nothing on this path is appended to the audit ledger. The append-only record is
of fetches and administrative acts; a row per client per poll would grow the
ledger to record that somebody asked a question rather than received content.
The signal is a counter instead — `skills_gateway.facade.held_content_answers`,
tagged by the state answered, so an operator can see that clients are checking
in and that some are still holding withdrawn content.

How to deploy the check across a fleet is in
[making the gateway the only door](../guides/client-enforcement.md#noticing-that-something-you-already-hold-was-withdrawn).

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| 401 on every request | Token missing, mistyped, or revoked. Remember the value goes in the **password** field. |
| 404 for a marketplace you registered | No snapshot has ever been approved, so no published repository exists. |
| 404 with an odd name | The name failed `^[a-z0-9][a-z0-9_-]{0,62}$` — the character set, or longer than 63 characters. |
| `git push` rejected | Expected — the facade is read-only by construction. Publishing to a hosted marketplace goes to `/publish/{name}`. |
| Client keeps getting an old SHA | Also expected. The published ref moves only on approval. |
