# Design: upstream-github-app

## Context

See proposal.md, "Why". The code this builds on:

- **`UpstreamGit`** (#497) is the one door to an upstream. `probe` and
  `fetchDefaultBranch` configure every JGit command through `connect`.
- **`UpstreamCredentials`** (#498) validates the list at startup, selects the
  longest prefix for a marketplace URL, and builds a per-command
  `HttpConnectionFactory` that sets `Authorization` only on requests under that
  prefix, drops any `Authorization` JGit sets, and never lets the JDK follow a
  redirect. That factory is the reason #498's guarantees can hold unchanged: it
  does not care where the header value came from.
- **`UpstreamFailure`** (#497) is the one translator of an upstream failure,
  and `scrub` replaces configured secrets in it.

## Goals / Non-Goals

**Goals:**
- A GitHub App entry is a drop-in for a static one: same prefix matching, same
  connection factory, same startup refusal, same scrub.
- An installation token that can read exactly one repository, for at most an
  hour, and nothing else.

**Non-Goals:**
- Rotating the private key without a restart. The key is configuration, bound
  once; what rotates without a restart is the installation token.
- Credentialing the forge metadata lookup or external plugin sources (#498).
- GitHub App authentication for anything but reading (no write permission is
  ever requested).

## Decisions

1. **Config shape: a nested `github-app` block on the same entry.**
   ```yaml
   - url-prefix: https://github.com/acme/
     github-app:
       app-id: "123456"
       private-key: ${SGW_UPSTREAM_APP_KEY}
       installation-id: "7890"        # optional
       api-url: https://api.github.com  # optional, the default
   ```
   - Exactly one kind: an entry with `github-app` must have neither `username`
     nor `token`; one without must have both. Anything else stops startup.
   - The ids are strings in the record, validated as positive integers, so a
     `${…}` left unresolved gets #498's message rather than a binder error.
   - *Alternative:* a `kind:` discriminator with flat keys. Rejected: it makes
     every key optional in every kind and moves the "which keys go together"
     rule from the shape into validation.

2. **The per-fetch header is resolved before the command runs.**
   `UpstreamCredentials.Selected.access(url, renew)` returns the
   `Authorization` value for this fetch. For a static entry it is the fixed
   Basic header. For an App entry it is `Basic base64(x-access-token:<token>)`
   from `GitHubAppTokens`. `connectionFactory(header)` is #498's factory, given
   the header instead of computing it. So the prefix check, the redirect rule
   and the JGit-header drop are the same code for both kinds.

3. **Minting** (`GitHubAppTokens`):
   - The JWT: RS256, `iss` = app id, `iat` = now − 60 s (GitHub's documented
     allowance for clock drift), `exp` = now + 9 min (GitHub refuses more than
     10). Signed with nimbus-jose-jwt's `RSASSASigner`, already on the classpath
     through Spring Security's OAuth2 support. It is built per mint and never
     kept.
   - Installation id: the configured one, or
     `GET {api-url}/repos/{owner}/{repo}/installation` with the JWT.
   - Token: `POST {api-url}/app/installations/{id}/access_tokens` with body
     `{"repositories":["<repo>"],"permissions":{"contents":"read"}}`.
     `metadata: read` is implied by GitHub for any repository selection.
   - Owner and repository are the first two path segments of the marketplace
     URL, the second without `.git`. Both must match `[A-Za-z0-9._-]+`, not be
     `.` or `..`, and there must be no third segment; otherwise the fetch fails
     with "the upstream URL does not name a GitHub repository" before any API
     call. This is how the URL is kept from choosing an API path.

4. **The API client.** A JDK `HttpClient` with a 10 s connect timeout, a 30 s
   request timeout, `Redirect.NEVER`, and the JVM's default proxy selector (as
   JGit uses). Never following a redirect is what keeps the JWT on the
   configured host (GW_AUTH_0051 — A GitHub App assertion is sent only to the
   configured API): a 3xx from the API is a failure.
   - **No SSRF address guard.** `SourceAddressPolicy` exists for URLs an
     upstream's manifest chooses. `api-url` is the operator's configuration,
     like the prefix itself, and GitHub Enterprise Server commonly sits on a
     private address the guard would refuse. The URL cannot redirect it
     elsewhere (above), and it is validated at startup like a prefix: https,
     or http only to loopback, no userinfo, query or fragment.

5. **Cache**, keyed by the entry's index and `owner/repo` (the entry fixes the
   App, and the owner fixes the installation):
   - An entry is used while `expires_at` is more than 5 minutes away.
     GitHub's tokens live an hour, so one mint serves a repository for about
     55 minutes of fetches.
   - `access(url, renew=true)` drops the cached token and mints anew.
     `UpstreamGit` calls it once, only when the first attempt used a *cached*
     token and git refused it ("repository not found or requires
     authentication"). A token minted for this fetch that git refuses is not
     retried: it will not get better, and a loop would hammer the API.
   - A refusal also evicts the entry, so the next fetch mints.
   - Concurrent fetches may both mint; the later one wins the cache. Harmless,
     and cheaper than a lock around an HTTP call.

6. **Failures.** `GitHubAppTokens` throws an `UpstreamException` carrying an
   `UpstreamFailure` with an App-specific reason. `UpstreamGit.probe` rethrows
   an `UpstreamException` as is rather than translating it again. Reasons:

   | API answer | Reason | Next step |
   |---|---|---|
   | 404 on `/repos/…/installation` | the GitHub App is not installed for this repository | install on the owner, select the repository |
   | 422 on `access_tokens` | same | add the repository to the installation's selection |
   | 404 on `access_tokens` | the GitHub App installation was not found | reinstall, or correct `installation-id` |
   | 403 on `access_tokens` whose message says suspended | the GitHub App installation is suspended | unsuspend it in the owner's settings |
   | 401, message names `iat`/`exp`, or a `Date` more than 30 s off | the gateway's clock differs from the GitHub API's | sync the host clock (NTP) |
   | other 401 | the GitHub API refused the App's key | check `app-id` matches the key and the key is not deleted |
   | connect/timeout/IO | the GitHub API could not be reached | check `api-url`, egress, proxy |
   | anything else, 3xx and other 403s (rate limits) included | the GitHub API did not issue a token | the status and GitHub's `message` |

   The root cause is `HTTP <status>: <GitHub's message>`, capped at 200
   characters and scrubbed. GitHub's messages never quote the JWT, but the
   scrub runs anyway.

7. **Nothing repeats a secret.**
   - The PEM lives in the properties record (whose `toString` omits it) and,
     parsed, in `GitHubAppTokens` as an `RSAPrivateKey`. The JWT is a local.
   - `UpstreamCredentials.secrets()` returns the static tokens, every PEM, and
     every installation token currently cached, so `UpstreamGit`'s scrub covers
     them. An evicted token cannot be in a later message: it is never sent
     again.
   - No log line in the new code names a token, a key or a JWT; the one DEBUG
     line names the entry, the repository and the expiry.

8. **PKCS#1 without BouncyCastle.** GitHub issues `BEGIN RSA PRIVATE KEY`
   (PKCS#1), which the JDK's `KeyFactory` does not read directly and nimbus
   reads only with BouncyCastle. The PKCS#1 bytes are wrapped in the fixed
   PKCS#8 `PrivateKeyInfo` envelope (version 0, `rsaEncryption`, the key as an
   octet string) with a small DER length encoder, then read with
   `PKCS8EncodedKeySpec`. `BEGIN PRIVATE KEY` is read as is. Anything else,
   including an encrypted key, a non-RSA key or bad base64, stops startup.
   *Alternative:* add BouncyCastle. Rejected: a large dependency for twenty
   lines.

9. **The ledger names the kind.** `credential=<prefix>` gains ` (github-app)`
   for an App entry. A static entry's detail is unchanged, so #498's test and
   existing ledgers still read the same. The app id and installation id are
   not secrets (both appear in GitHub's own URLs), but they are configuration
   an auditor can read there, so the ledger does not copy them.

10. **Which requirement IDs.** The seven `GW_INGEST_*` are ingestion behaviour.
    The three `GW_AUTH_*` are about the App's credentials as credentials:
    never repeated, short-lived, sent only to the API.

## Risks / Trade-offs

- **[A key rotated at GitHub while the gateway runs]** → Mints fail with "the
  GitHub API refused the App's key" until the new key is configured and the
  gateway restarted. Documented. GitHub allows two active keys, so rotation is:
  add a key, redeploy with it, delete the old one.
- **[An installation token outliving a de-selection]** → A token minted before
  the repository was removed from the installation keeps working until it
  expires (at most an hour). That is GitHub's model; the cache adds at most the
  token's own lifetime.
- **[A redirect inside the prefix to another repository]** → Carries the token,
  as a static token would; the token can read only the one repository, so the
  other repository refuses it.
- **[GitHub changes an error's wording]** → Only the clock-skew row reads a
  message; the `Date` header check backs it up. A miss degrades to "the GitHub
  API refused the App's key", which is still a correct next step.

## Migration Plan

No schema change. Existing static entries are unchanged in shape and
behaviour. Rollback: revert; an App entry then fails startup as an unknown
shape (both `username` and `token` blank), which is the safe direction.
