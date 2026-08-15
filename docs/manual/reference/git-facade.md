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

`{marketplace}` is validated against `^[a-z0-9][a-z0-9_-]*$` after stripping an
optional `.git` suffix. Anything else is a 404 — which is also what blocks path
traversal into arbitrary directories.

## Authentication

HTTP Basic, backed solely by the PAT provider. An OIDC session can never
authenticate a git fetch, because no OIDC provider is registered in this chain.

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
    touch `/git/**`. A valid PAT is still required.

## What is served

Exactly one ref, `refs/heads/main`, from
`{data-dir}/published/{marketplace}.git`.

Repository resolution opens only the published path and returns nothing unless
`refs/heads/main` resolves. A marketplace that has been registered and ingested
but never approved therefore returns **404**: there is nothing to serve, and the
quarantine repository is not reachable from here by any code path.

The served SHA changes only when a reviewer approves a snapshot.

## Writes are impossible

Receive-pack is disabled by construction — the servlet is configured with a null
receive-pack factory, so no `ReceivePack` can be created at all. `git push`
receives the standard "service not enabled" rejection.

There is no write-side endpoint, filter or hook to misconfigure. This is a
structural guarantee, not a policy one.

## Auditing

Two hooks append to the ledger:

| Event | When |
| --- | --- |
| `info-refs` | Ref advertisement, recording the resolved `main` SHA. |
| `upload-pack` | One entry per wanted object when the packfile is served. |

Each entry carries the client address as `source`, the PAT's principal, the
marketplace, the ref and the SHA. Negotiation rounds are not recorded.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| 401 on every request | Token missing, mistyped, or revoked. Remember the value goes in the **password** field. |
| 404 for a marketplace you registered | No snapshot has ever been approved, so no published repository exists. |
| 404 with an odd name | The name failed `^[a-z0-9][a-z0-9_-]*$`. |
| `git push` rejected | Expected — the facade is read-only by construction. |
| Client keeps getting an old SHA | Also expected. The published ref moves only on approval. |
