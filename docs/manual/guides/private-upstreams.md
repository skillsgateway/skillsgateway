# Reading a private upstream

By default the gateway reads every upstream anonymously. A private or internal
repository then answers "repository not found or requires authentication", and
registration refuses it. To read one, give the gateway a credential for the
repository's URL prefix: a static token, or, on GitHub, a
[GitHub App](#using-a-github-app-instead-of-a-token).

## What a credential is

An entry under `skills-gateway.ingestion.upstream-credentials` is either a
static token or a [GitHub App](#using-a-github-app-instead-of-a-token). A
static entry has three fields:

- `url-prefix`: which upstream URLs the entry covers;
- `username`: sent as the HTTP Basic user;
- `token`: sent as the HTTP Basic password.

A forge personal access token works as the token. The username is whatever the
forge expects alongside it; GitHub accepts any non-empty value.

```yaml
skills-gateway:
  ingestion:
    upstream-credentials:
      - url-prefix: https://github.com/acme/
        username: skills-gateway
        token: ${SGW_UPSTREAM_ACME_TOKEN}
      - url-prefix: https://git.example.com/
        username: skills-gateway
        token: ${SGW_UPSTREAM_INTERNAL_TOKEN}
```

- **The longest matching prefix wins.** With the entries above,
  `https://github.com/acme/skills.git` uses the acme token, and
  `https://github.com/other/skills.git` uses no token at all. Prefixes, rather
  than hosts, are there because a GitHub fine-grained token is limited to one
  owner. Two owners on one host need two tokens.
- **Prefixes match whole path segments.** `https://github.com/acme` and
  `https://github.com/acme/` are the same prefix. Neither covers
  `https://github.com/acme-labs/…`.
- **Registration and ingestion use the same credential.** The registration
  check, every ingest and every sync fetch go through one connection path, so
  they cannot disagree about whether an upstream is readable.
- **The token is always an environment reference.** Write `${SOME_VARIABLE}`,
  never the token itself. The sections below show how to set that variable on
  each platform.

The full contract, with the validation rules, is in
[Configuration — upstream credentials](../reference/configuration.md#ingestion-upstream-credentials).

!!! warning "The token's scope is the limit"

    Registering a marketplace under a credentialed prefix can pull anything the
    token can read into quarantine. Reviewers can read it there, and it can be
    approved and served. Registration is restricted to administrators, and
    nothing else narrows it: the gateway does not keep a list of repositories
    per prefix. **Scope the token to exactly the repositories that may be
    ingested.** Use a fine-grained, read-only token limited to those
    repositories, not a token for a whole organisation or a person's account.
    The ledger names the prefix every registration was read with
    (`credential=<url-prefix>` on `marketplace-registered`), so an auditor can
    list what was registered under each credential.

## Where the credential goes, and where it does not

- **Only to its own prefix.** The credential is chosen once, from the
  marketplace's clone URL. The gateway attaches it to each HTTP request itself,
  and only when that request's URL is under the chosen prefix. A redirect to
  another host, port, scheme or path gets no credential. So does a redirect
  into a prefix that has a credential of its own: a server's answer never
  decides which token is sent.
- **Only over HTTPS.** A prefix must be `https`. Plain `http` is accepted only
  to a loopback host (`localhost`, `127.0.0.0/8`, `::1`), where no network
  carries the token.
- **Never repeated.** The token does not appear in the log, the audit ledger,
  the marketplace record, a failure reason or any API response. It is not
  stored in the database. A failure message that quotes it has it replaced
  with `***`.
- **Not in a clone URL.** Registration refuses a URL with userinfo, such as
  `https://user:token@host/…`, with `400`. A token in a URL would be stored
  with the marketplace and shown wherever the URL is.
- **Not for the forge's REST API.** The best-effort metadata lookup at
  registration (project name, description, last update) is anonymous, so for
  a private repository those fields stay empty.
- **Not for external plugin sources.** A manifest's external sources are
  resolved anonymously, whatever credentials are configured.

## Supplying the token

=== "Local development"

    Keep the token in a file outside the repository, and read it into the
    environment when you start the gateway:

    ```console
    $ install -m 600 /dev/null ~/.config/skills-gateway/acme-token
    $ $EDITOR ~/.config/skills-gateway/acme-token
    $ SGW_UPSTREAM_ACME_TOKEN="$(cat ~/.config/skills-gateway/acme-token)" ./mvnw spring-boot:run
    ```

    Put the `upstream-credentials` block in a configuration file that is also
    outside the repository, and point `SPRING_CONFIG_ADDITIONAL_LOCATION` at its
    directory. See
    [Supplying a configuration file](deploying-without-kubernetes.md#supplying-a-configuration-file).
    The file then holds only the `${SGW_UPSTREAM_ACME_TOKEN}` reference, never
    the token.

=== "Kubernetes (Helm)"

    The entry goes in the chart's `config`. The token goes in a Secret, and
    `extraEnv` exposes it through `secretKeyRef`:

    ```console
    $ kubectl create secret generic skills-gateway-upstreams \
        --from-file=acme-token=./acme-token
    ```

    ```yaml
    config:
      skills-gateway:
        ingestion:
          upstream-credentials:
            - url-prefix: https://github.com/acme/
              username: skills-gateway
              token: ${SGW_UPSTREAM_ACME_TOKEN}

    extraEnv:
      - name: SGW_UPSTREAM_ACME_TOKEN
        valueFrom:
          secretKeyRef:
            name: skills-gateway-upstreams
            key: acme-token
    ```

    `config` is a ConfigMap, so it only ever holds the reference. See
    [Deploying on Kubernetes](deploying-on-kubernetes.md#single-settings-and-secrets-extraenv-extraenvfrom).

=== "AWS ECS / Fargate"

    Store the token in Secrets Manager and map it into the container with the
    task definition's `secrets`. Supply the entry itself as configuration, for
    example through `SPRING_APPLICATION_JSON` or indexed variables (see
    [Property names as environment variables](deploying-without-kubernetes.md#lists)):

    ```json
    {
      "containerDefinitions": [{
        "name": "skills-gateway",
        "environment": [
          {"name": "SKILLSGATEWAY_INGESTION_UPSTREAMCREDENTIALS_0_URLPREFIX", "value": "https://github.com/acme/"},
          {"name": "SKILLSGATEWAY_INGESTION_UPSTREAMCREDENTIALS_0_USERNAME", "value": "skills-gateway"},
          {"name": "SKILLSGATEWAY_INGESTION_UPSTREAMCREDENTIALS_0_TOKEN", "value": "${SGW_UPSTREAM_ACME_TOKEN}"}
        ],
        "secrets": [
          {"name": "SGW_UPSTREAM_ACME_TOKEN",
           "valueFrom": "arn:aws:secretsmanager:eu-north-1:111122223333:secret:skills-gateway/upstream-acme"}
        ]
      }]
    }
    ```

    Spring takes a list from one configuration source as a whole. Keep every
    field of every entry in the same place (all in the environment, as here, or
    all in one file), or an entry loses the fields declared elsewhere and the
    gateway refuses to start.

    The task **execution role** needs `secretsmanager:GetSecretValue` on that
    secret, plus `kms:Decrypt` if it is encrypted with a customer-managed key.
    ECS resolves the secret when the task starts. The value never appears in
    the task definition.

## Rotating a token

The credentials are read once, at startup. A rotated token reaches the gateway
on its **next start**:

1. Issue the new token at the forge.
2. Update the secret: the file, the Kubernetes Secret or the Secrets Manager
   value.
3. Restart the gateway. On Kubernetes, `kubectl rollout restart`. On ECS,
   force a new deployment.
4. Revoke the old token.

Between steps 3 and 4 both tokens work, so nothing fails during the rotation.

## Using a GitHub App instead of a token

On GitHub, a GitHub App is the better credential for an organisation's
repositories:

- **No person's token.** A personal access token belongs to someone, lasts as
  long as they keep it, and leaves with them. An App belongs to the
  organisation.
- **Installed where it may read.** The App reads only the repositories it is
  installed on, and the organisation owner decides which those are.
- **Hour-long, one-repository tokens.** For every fetch the gateway mints an
  installation token that can read the contents of that one repository and
  nothing else, and that expires within the hour. A token that escapes is
  worth little.
- **Tokens rotate themselves.** Nothing has to be rotated by hand except the
  App's private key, and a key change is still a restart.

### Create and install the App

1. In the organisation's settings, open **Developer settings → GitHub Apps →
   New GitHub App**. Give it a name and a homepage URL, and clear
   **Webhook → Active**: the gateway receives no events.
2. Under **Repository permissions**, set **Contents** to **Read-only**.
   **Metadata** is read-only and required. Grant nothing else.
3. Create the App, note its **App ID**, and under **Private keys** generate a
   key. GitHub downloads a `.pem` file (`BEGIN RSA PRIVATE KEY`). Keep it
   outside every repository.
4. **Install App** on the organisation, and choose **Only select
   repositories** with the marketplace repositories the gateway may ingest.
   The installation's page URL ends in its installation id; configuring it saves
   one API call per token, and leaving it out lets the gateway look it up per
   repository.

For GitHub Enterprise Server, do the same on your server and set `api-url` to
`https://<your-server>/api/v3`.

### Configure the entry

```yaml
skills-gateway:
  ingestion:
    upstream-credentials:
      - url-prefix: https://github.com/acme/
        github-app:
          app-id: "123456"
          private-key: ${SGW_UPSTREAM_APP_KEY}
          installation-id: "7890"      # optional
          # api-url: https://api.github.com   (the default)
```

An App entry has no `username` or `token`. The marketplace URL must name a
repository directly, `https://github.com/<owner>/<repository>[.git]`. The
gateway reads the owner and repository from it, and they must be plain GitHub
names.

The key is supplied like a token, by environment reference:

=== "Local development"

    Keep the downloaded key outside the repository and read it into the
    environment when you start the gateway:

    ```console
    $ install -m 600 ~/Downloads/acme-skills.*.private-key.pem ~/.config/skills-gateway/app-key.pem
    $ export SGW_UPSTREAM_APP_KEY="$(cat ~/.config/skills-gateway/app-key.pem)"
    $ ./mvnw spring-boot:run
    ```

=== "Kubernetes (Helm)"

    Put the key in a Secret and expose it with `extraEnv`, as for a token:

    ```console
    $ kubectl create secret generic skills-gateway-upstreams \
        --from-file=app-key=./acme-skills.private-key.pem
    ```

    ```yaml
    config:
      skills-gateway:
        ingestion:
          upstream-credentials:
            - url-prefix: https://github.com/acme/
              github-app:
                app-id: "123456"
                private-key: ${SGW_UPSTREAM_APP_KEY}

    extraEnv:
      - name: SGW_UPSTREAM_APP_KEY
        valueFrom:
          secretKeyRef:
            name: skills-gateway-upstreams
            key: app-key
    ```

=== "AWS ECS / Fargate"

    Store the PEM as a Secrets Manager secret. A multi-line value works as a
    secret string, for example
    `aws secretsmanager create-secret --name skills-gateway/upstream-app-key --secret-string file://acme-skills.private-key.pem`.
    Map it in with the task definition's `secrets`:

    ```json
    {
      "containerDefinitions": [{
        "name": "skills-gateway",
        "environment": [
          {"name": "SKILLSGATEWAY_INGESTION_UPSTREAMCREDENTIALS_0_URLPREFIX", "value": "https://github.com/acme/"},
          {"name": "SKILLSGATEWAY_INGESTION_UPSTREAMCREDENTIALS_0_GITHUBAPP_APPID", "value": "123456"},
          {"name": "SKILLSGATEWAY_INGESTION_UPSTREAMCREDENTIALS_0_GITHUBAPP_PRIVATEKEY", "value": "${SGW_UPSTREAM_APP_KEY}"}
        ],
        "secrets": [
          {"name": "SGW_UPSTREAM_APP_KEY",
           "valueFrom": "arn:aws:secretsmanager:eu-north-1:111122223333:secret:skills-gateway/upstream-app-key"}
        ]
      }]
    }
    ```

    A store that keeps the key on one line with literal `\n` sequences works
    too: the gateway reads `\n` as a line break. The execution role needs the
    same permissions as for a token.

### What happens on each fetch

1. The gateway signs a short assertion with the App's key: issued a minute in
   the past to allow for clock drift, and expiring nine minutes later.
2. If no `installation-id` is configured, it asks
   `GET {api-url}/repos/<owner>/<repository>/installation`.
3. It asks `POST {api-url}/app/installations/<id>/access_tokens` for a token
   for **that one repository**, with `contents: read` only.
4. It reads the repository with that token as `x-access-token`, under exactly
   the prefix rules above. The assertion never goes to the git host.

The token is reused for the same repository until five minutes before it
expires. If the upstream refuses a reused token, for example because the
repository was just removed from the installation, the gateway mints once more
and retries once. A second refusal is reported as "repository not found or
requires authentication".

The API call goes only to `api-url`. Nothing in a marketplace URL can change
it, and the gateway does not follow a redirect from it.

### When a token cannot be minted

Registration answers `502`, and an ingest records the failure, with one of
these reasons:

| Reason | What to do |
| --- | --- |
| the GitHub App is not installed for this repository | Install the App on the repository's owner, or add the repository to the installation's selected repositories. |
| the GitHub App installation was not found | The `installation-id` is wrong or the App was uninstalled. Correct it, or omit it. |
| the GitHub App installation is suspended | Unsuspend the installation in the owner's settings. |
| the GitHub API refused the App's key | The `app-id` does not match the key, or the key was deleted from the App. |
| the gateway's clock differs from the GitHub API's | Synchronise the host clock with NTP. |
| the GitHub API could not be reached | Check `api-url`, and the network path, proxy and egress rules to it. |
| the GitHub API did not issue a token | Any other answer. The root cause gives the status and GitHub's message. |
| the upstream URL does not name a GitHub repository | Register the repository's own `https://<host>/<owner>/<repository>` URL. |

### Rotating the App's key

GitHub lets an App have two keys at once, so a key rotates without downtime:

1. Generate a second key in the App's settings.
2. Update the secret with the new key and restart the gateway.
3. Delete the old key in the App's settings.

The ledger marks a registration made under an App as
`credential=<url-prefix> (github-app)`. The warning above still applies: the
installation's repository selection is the limit on what can be registered.

## When the gateway refuses to start

The gateway refuses to start when an entry cannot work as written. The message
names the entry by its position and prefix, never by its token:

| Message says | Cause |
| --- | --- |
| `still holds an unresolved ${...} reference` | The environment variable the entry refers to is not set. Without this check, Spring would send the literal text `${…}` as the password. |
| `is blank` | The username or token is empty. |
| `must use https` | An `http` prefix to a host that is not loopback. |
| `must be an absolute http(s) URL with no userinfo, query, fragment, …` | The prefix is not a plain `https://host[:port]/path` URL. |
| `already declares` | Two entries for the same prefix. Which token applied would be an accident of ordering. |
| `declares both a username/token and a github-app` | An entry is one kind or the other. |
| `declares no credential` | An entry with a prefix and nothing else. |
| `app-id must be the App's numeric id` / `installation-id must be …` | A `github-app` id that is not a positive number. |
| `private-key is not a readable RSA private key in PEM form` | The key is truncated, encrypted, not RSA, or not PEM. The message never quotes it. |
| `github-app.api-url must be an absolute https URL …` | An `http` API URL to a host that is not loopback, or one with userinfo, a query or a fragment. |

## Not planned

SSH keys, per-marketplace credentials and credentials stored in the database
are not planned.
