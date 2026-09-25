# Design: upstream-credentials

## Context

For the motivation, see proposal.md, "Why". The relevant facts about the code:

- **One door.** `UpstreamGit` (#497) is the only code that reaches a
  marketplace upstream. `probe` (registration) and `fetchDefaultBranch`
  (ingestion) both configure their JGit command through one private
  `connect(command)`.
- **JGit's own credential flow is not safe enough here.** With a
  `CredentialsProvider`, JGit sends no credential until a `401`. It then keeps
  the resulting `Authorization` header across redirects, and resets it only
  when the redirect's *host name* differs (`TransportHttp.redirect`). A
  redirect to the same host on another port, another scheme or another path
  keeps the header. For a prefix such as `https://github.com/acme/`, a
  redirect to `https://github.com/other/` would carry acme's token.
- **The address guard is not applied to upstreams.** #497 decided that
  `SourceAddressPolicy` and `GuardedHttpConnectionFactory` stay on external
  plugin sources only. This change keeps that decision. The only control on
  an upstream's URL is the scheme allowlist.
- **Userinfo is not refused at registration today.** A URL such as
  `https://user:secret@host/repo.git` is accepted, stored, and echoed in the
  marketplace read.

## Goals / Non-Goals

**Goals:**
- One place that decides, per HTTP request, whether that request carries a
  credential.
- Startup that fails loudly on an entry that cannot work as written.

**Non-Goals:**
- Extending the address policy to upstreams (see Context).
- A second credential kind. GitHub App tokens come later under the same
  matching.
- A per-marketplace credential choice, or any credential in the database.

## Decisions

1. **The gateway attaches the credential, not JGit.**
   - `UpstreamCredentials` builds a per-command `HttpConnectionFactory`. It
     wraps JGit's `JDKHttpConnectionFactory`, and is installed through
     `TransportConfigCallback` on that one transport, like
     `GuardedHttpConnectionFactory`. It is never installed through the
     JVM-wide static.
   - On each `create(url)` it sets `Authorization: Basic ...` only when that
     request URL is under the credential's prefix.
   - It ignores any `Authorization` that JGit sets itself. It also keeps the
     JDK from following redirects, so every hop is a new `create` that it
     decides again.
   - JGit is given no `CredentialsProvider`. A `401` with a wrong token
     therefore ends in JGit's `noCredentialsProvider` error, which
     `UpstreamFailure` already translates to "repository not found or requires
     authentication".
   - *Alternative:* `UsernamePasswordCredentialsProvider` that checks the
     `URIish` it is asked about. Rejected, because JGit asks only on a `401`
     and then carries the header across same-host redirects without asking
     again (Context). The negative tests are written against that alternative
     first, to show they fail.
   - **Preemptive.** The header goes on the first request under the prefix,
     without waiting for a challenge. Forges accept it, and it removes the
     401-then-retry state in which JGit would hold the header.
2. **The credential is chosen once, from the marketplace URL.**
   - `connect(command, url)` finds the entry whose prefix is the longest match
     for the marketplace URL. Every request that fetch or listing makes
     carries that entry's credential if the request is under that entry's
     prefix, and nothing otherwise.
   - A redirect into *another* credentialed prefix does not pick up that
     prefix's token. The credential a fetch may use is the one its URL
     selected.
   - A marketplace URL under no prefix gets no factory at all. Its path is
     exactly #497's.
3. **Matching is structural.** Both the prefix and the URL are parsed with
   `java.net.URI`.
   - The scheme and host are compared case-insensitively. The port is compared
     after defaults (443 and 80).
   - The path is compared by whole segments. The prefix's trailing `/` is
     optional and means nothing, so `https://github.com/acme` and
     `https://github.com/acme/` are the same prefix, and neither matches
     `https://github.com/acme-evil/x.git`.
   - A request URL gets no credential when it has userinfo, when its raw path
     contains `%2e`, `%2f` or `%5c` (any case) or a backslash, or when
     `URI.normalize()` would change its path. Those are the spellings that
     read as inside the prefix to a string comparison and outside it to a
     server.
   - The query is ignored. JGit's `?service=git-upload-pack` is not part of
     the prefix.
4. **Startup validation fails closed.** `UpstreamCredentials` validates in its
   constructor, so a bad entry stops the context.
   - **Unresolved reference.** A token or username that still contains
     `${`. Spring Boot leaves an unresolvable placeholder as its literal text,
     so without this check a missing environment variable would send
     `${SGW_TOKEN}` as the password.
   - **Blank** username or token.
   - **Scheme.** `https`, or `http` only to a loopback host (`localhost`,
     `127.0.0.0/8`, `::1`), where no network carries the token. There is no
     setting to allow cleartext elsewhere.
   - **Shape.** No userinfo, query or fragment in the prefix, and no two
     entries with the same normalised prefix.
   - Every message names the entry by its index and prefix, never by its
     token.
5. **Nothing repeats the token.**
   - The token exists only in the properties record and in the factory. The
     record's `toString` omits it, as `Mirror`'s does.
   - `UpstreamFailure.scrub(secrets)` replaces every configured token value
     in the reason, next step and root cause. `UpstreamGit` applies it to
     every failure it translates. JGit cannot quote the token, because it is
     in no URL, but a message from a misbehaving server could.
   - The WARN log line in registration and ingestion logs `describe()`, which
     is the scrubbed text.
6. **Registration refuses userinfo in a URL** with `400`, before any network
   call. The check is a string check on the authority as well as
   `URI.getRawUserInfo()`, so an unparseable URL that still contains `@` in
   its authority is refused. `MirrorUrlPolicy` refuses its URLs the same way.
7. **The ledger names the prefix.** The `marketplace-registered` detail gains
   `credential=<url-prefix>` when an entry applies. Auditors can list what was
   registered under a credential. The token never appears.
8. **The token's scope is the limit.** No new restriction on who may register.
   - Registration through the API already requires the global `admin` role.
   - Estate declarations are written by the same operator who configures the
     credential.
   - A narrower control, such as a role or an allowlist of repositories per
     prefix, would add a grantable role or a configuration leaf. Either would
     duplicate what the token's own scope already expresses at the forge.
   - The docs say it plainly: register under a credentialed prefix and
     anything the token can read can be pulled into quarantine, where
     reviewers read it. Scope the token to what may be ingested.
9. **Configuration, not an estate object.** The estate (#65) converges objects
   that the API also manages. A credential is never API-managed. It has no
   endpoint and no row, and it cannot have one without being stored. So there
   is nothing to extend in `skills-gateway.estate.*` and no group mapping to
   keep compatible. Helm and ECS already pass configuration and secrets to the
   gateway (`config` plus `extraEnv` with `secretKeyRef`, and a task
   definition's `secrets`), so no chart change is needed. Only documentation
   is added.
10. **Rotation is a restart.** The properties are bound once. A rotated secret
    reaches the gateway on its next start. That is stated in the docs. A
    GitHub App (later) is the rotation-without-restart answer.

## Risks / Trade-offs

- **[A forge that answers a preemptive header differently from a challenged
  one]** → The major forges accept preemptive Basic for git over HTTPS. The
  fixture exercises both a forge that demands the credential and one that
  ignores it.
- **[A token scoped wider than intended]** → This is documented as the limit
  (decision 8). The ledger names the prefix of every credentialed
  registration.
- **[A same-host redirect inside the prefix, for example after a repository
  rename]** → It carries the credential. That is intended: it is still under
  the prefix.
- **[Cleartext http to a non-loopback forge]** → Refused at startup. A lab
  forge on plain http cannot use a credential. That is deliberate.
- **[DNS rebinding or address tricks on the prefix's host]** → Out of scope,
  as for anonymous upstreams (Context). The token goes to whatever the
  prefix's host name resolves to, which is the operator's configuration.

## Migration Plan

No schema change. A registered marketplace whose URL has userinfo keeps
working until it is next registered. Pre-1.0, there is no deployed estate to
migrate. Rollback: revert, and the upstreams are anonymous again.
