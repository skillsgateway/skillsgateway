# Try it out

Run a gateway on your machine, let it quarantine and vet two public skill
marketplaces, approve one, and fetch it with `git`. You need Docker and about ten
minutes. Nothing is installed, and nothing is configured beyond what is on this
page.

By the end you will have seen the whole
[lifecycle](../concepts/lifecycle.md): content comes in held, is vetted before
anyone can see it, and is served only once someone approves it.

## 1. Start the gateway

Look up the latest release, which is the image tag to run:

```console
$ SGW_VERSION=$(curl -fsSL -o /dev/null -w '%{url_effective}' \
    https://github.com/skillsgateway/skillsgateway/releases/latest | sed 's|.*/||')
$ echo $SGW_VERSION
```

The gateway needs PostgreSQL. Start both on a private Docker network:

```console
$ docker network create skills-gateway-try
$ docker run -d --name skills-gateway-try-db --network skills-gateway-try \
    -e POSTGRES_DB=skillsgateway \
    -e POSTGRES_USER=skillsgateway \
    -e POSTGRES_PASSWORD=skillsgateway \
    postgres:18
$ docker run -d --name skills-gateway-try --network skills-gateway-try -p 8080:8080 \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://skills-gateway-try-db:5432/skillsgateway \
    -e SPRING_DATASOURCE_USERNAME=skillsgateway \
    -e SPRING_DATASOURCE_PASSWORD=skillsgateway \
    -e SKILLSGATEWAY_DEVINSECUREAUTH=true \
    -e SKILLSGATEWAY_ESTATE_MARKETPLACES_0_NAME=reqstool-ai \
    -e SKILLSGATEWAY_ESTATE_MARKETPLACES_0_URL=https://github.com/reqstool/reqstool-ai.git \
    -e SKILLSGATEWAY_ESTATE_MARKETPLACES_1_NAME=oncall-kit \
    -e SKILLSGATEWAY_ESTATE_MARKETPLACES_1_URL=https://github.com/anthropics/oncall-kit.git \
    ghcr.io/skillsgateway/skillsgateway:$SGW_VERSION
```

The `ESTATE_MARKETPLACES` lines register the two demo marketplaces at startup,
through [declarative estate configuration](declarative-estate.md). Both are
small, public Claude Code plugin marketplaces.

!!! danger "`SKILLSGATEWAY_DEVINSECUREAUTH` turns off login"

    It lets you in without an identity provider, as a synthetic administrator
    called `dev`. The whole web surface is unauthenticated while it is on. Use it
    on your own machine only — see
    [Local development](local-development.md#without-one-the-escape-hatch).

The gateway is ready when this answers `UP`, usually within half a minute:

```console
$ curl localhost:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}
```

## 2. Open the portal

Go to <http://localhost:8080> and open **Marketplaces**. Both demo marketplaces
are listed, and neither has a snapshot yet: registering a marketplace fetches
nothing, so until now the gateway has not read a byte of either repository.

## 3. Ingest

Open **reqstool-ai** and press **Ingest**. Do the same for **oncall-kit**.

The gateway clones each repository at its current commit into quarantine, which
no client can read, and runs the [vetting chain](../concepts/vetting.md) on it.
After a few seconds each marketplace has one snapshot, in state **held**.

## 4. Read the vetting report

Open the held snapshot of **reqstool-ai**. Its card opens on the **Vetting** tab:
the chain's outcome, then one verdict per vetter — secrets, prompt injection,
executable surface, licenses, skill conformance — with every finding and the
file and line it points at. Both demo marketplaces come out **clear**.

The **Contents** tab shows the files exactly as they will be served, before any
of them is.

## 5. Approve one

On the **reqstool-ai** snapshot, press **Approve**, read the dialog and confirm.
The snapshot is now **approved**, and this marketplace is served.

The gateway notes that `dev` both ingested and approved this snapshot. On a real
gateway that is a second person's job; here there is only you, so it warns and
lets it through.

Leave **oncall-kit** held.

## 6. Fetch it

Still on **reqstool-ai**, press **Connect a client**. Create a token in the
wizard, then copy and run the **Store the credential** line it shows. Then clone:

```console
$ git clone http://localhost:8080/git/reqstool-ai
```

You get the approved snapshot — the commit that was vetted, not whatever the
upstream holds now. The **Add the marketplace to Claude Code** line in the same
wizard adds it to Claude Code instead.

Now try the one you did not approve:

```console
$ git clone http://localhost:8080/git/oncall-kit
fatal: repository 'http://localhost:8080/git/oncall-kit/' not found
```

Held content is not served.

## 7. Look at the ledger

Open **Audit log**. Every step you took is there: the two registrations
(attributed to `config-reconciler`, because configuration made them), the
ingests, each vetter's verdict and your approval.

## Start clean instead

To start with nothing registered, leave out the four
`SKILLSGATEWAY_ESTATE_MARKETPLACES_*` lines in step 1. Then register a
marketplace yourself under **Marketplaces** → **Register marketplace** — any
public `https://` git repository that holds a plugin marketplace — and carry on
from step 3. [Registering a marketplace](registering-a-marketplace.md) covers
the form.

## Run it from source instead

With JDK 25 and Docker, a clone of the repository runs the same demo with no
PostgreSQL to start — the build starts one for you:

```console
$ ./mvnw spring-boot:run -Dspring-boot.run.arguments="--skills-gateway.dev-insecure-auth=true --skills-gateway.estate.marketplaces[0].name=reqstool-ai --skills-gateway.estate.marketplaces[0].url=https://github.com/reqstool/reqstool-ai.git --skills-gateway.estate.marketplaces[1].name=oncall-kit --skills-gateway.estate.marketplaces[1].url=https://github.com/anthropics/oncall-kit.git"
```

This runs the latest `main` rather than a release. Drop the four
`estate.marketplaces` arguments to start clean. See
[Local development](local-development.md) for the rest of that loop.

## Clean up

```console
$ docker rm -f skills-gateway-try skills-gateway-try-db
$ docker network rm skills-gateway-try
```

Your token and stored credential point at a gateway that no longer exists;
remove the credential with `git credential reject` or your credential manager.

## Next

- [Lifecycle — quarantine to serve](../concepts/lifecycle.md) — what you just
  did, and why each step is there.
- [Consuming approved skills](consuming-skills.md) — clients, credentials and
  the virtual catalog.
- [Deploying on Kubernetes](deploying-on-kubernetes.md) or
  [without Kubernetes](deploying-without-kubernetes.md) — a gateway for real,
  with an identity provider.
