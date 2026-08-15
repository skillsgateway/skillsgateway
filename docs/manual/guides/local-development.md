# Local development

Get a gateway running on your machine, with or without an identity provider.

## Prerequisites

- JDK 25 (Temurin; GraalVM CE 25 only for native builds)
- Docker — Testcontainers for the build, Compose for running
- `git`

Node and pnpm are provisioned by the Maven build. You do **not** need them to
build or run the gateway; they matter only for UI development loops.

## Run with Compose

The quickest path. Starts the gateway and PostgreSQL on port 8080:

```console
$ docker compose up
```

## Run from source

```console
$ ./mvnw verify
$ java -jar target/skills-gateway-*.jar
```

The build packages the React portal into the jar, so the running application
serves the portal at `/`.

## Database

The datasource is supplied entirely by environment — there is nothing in
`application.yaml`:

```console
$ export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/skillsgateway
$ export SPRING_DATASOURCE_USERNAME=skillsgateway
$ export SPRING_DATASOURCE_PASSWORD=skillsgateway
```

Flyway builds the schema from a single consolidated migration on startup. There
is nothing to configure and nothing to run by hand.

## Authentication

### With an identity provider

Point the OIDC variables at your IdP:

```console
$ export SGW_OIDC_CLIENT_ID=skills-gateway
$ export SGW_OIDC_CLIENT_SECRET=...
$ export SGW_OIDC_AUTHORIZATION_URI=https://idp.example.com/authorize
$ export SGW_OIDC_TOKEN_URI=https://idp.example.com/token
$ export SGW_OIDC_JWK_SET_URI=https://idp.example.com/jwks
```

The client registration id is `idp` and must exist at build time for
native-image, which is why the defaults are the placeholders `change-me` and
`idp.invalid`. A gateway started without these will not complete a login.

### Without one — the escape hatch

```console
$ java -jar target/skills-gateway-*.jar --skills-gateway.dev-insecure-auth=true
```

!!! danger "Never outside a development loop"

    This makes the **entire web surface** unauthenticated — all of `/api/**`,
    `/actuator/**` and `/docs` — and injects a synthetic principal `dev`, which
    is then what appears in every audit entry. The application logs a loud
    warning at startup.

    It does **not** affect the git facade: `/git/**` still requires a valid
    personal access token.

## Verify it is up

`/actuator/health` is the only unauthenticated endpoint:

```console
$ curl -s localhost:8080/actuator/health
{"status":"UP"}
```

Then open <http://localhost:8080/> and log in.

!!! tip "Probe the bare path"

    Use `/actuator/health` exactly. Health *subpaths* such as
    `/actuator/health/liveness` are not permitted by the security chain and
    redirect to the identity provider — which is why the Kubernetes manifests
    point both probes at the bare path.

## Storage

Git repositories live under `skills-gateway.data-dir`, which defaults to `data`
relative to the working directory. In the container image it is `/data`.

!!! warning "The Helm default is not durable"

    The chart mounts `/data` as an `emptyDir` unless you supply
    `persistence.existingClaim`. Losing that volume loses the quarantine and
    published repositories — everything else is rebuildable from the database
    and upstream, but approved refs are not.

## Observability

Traces, metrics and logs can be sent to a local **Grafana LGTM** stack — the
`grafana/otel-lgtm` image, which bundles an OpenTelemetry Collector with Loki
(logs), Tempo (traces), Prometheus (metrics) and Grafana. It is provisioned as
an [Arconia](https://arconia.io) dev service, so there is nothing to install and
no compose file to start.

It is **opt-in through the `observability` Spring profile** and off in every
other run: `./mvnw clean verify` and the e2e suite start no LGTM container and
attempt no OTLP export, which keeps the test loop fast and the logs quiet.

```console
$ ./mvnw spring-boot:run \
    -Dspring-boot.run.profiles=observability \
    -Dspring-boot.run.useTestClasspath=true
```

`useTestClasspath` is required: the dev-service and OpenTelemetry dependencies
are test scope so that they never reach the packaged jar, the container image or
the native binary. The same flag also gives you the PostgreSQL dev service, so
the datasource environment variables above are not needed for this run.

The stack takes a moment to pull on first use. Watch the log for the Grafana
URL, which is on a random port:

```
Dev Service 'lgtm' is ready — Grafana: http://localhost:<port>
```

Log in is not required (anonymous admin). Explore traces in **Tempo**, metrics
in **Prometheus** and logs in **Loki**; all three are pre-provisioned as Grafana
data sources by the image.

| Property | Default here | Purpose |
| --- | --- | --- |
| `arconia.dev.services.lgtm.enabled` | `false`, `true` under `observability` | Starts the LGTM container |
| `arconia.otel.enabled` | `false`, `true` under `observability` | Enables the OpenTelemetry SDK and OTLP export |

Ports are random by default; pin them with
`arconia.dev.services.lgtm.grafana-port` and friends if you want stable
bookmarks. See the
[Arconia dev services documentation](https://docs.arconia.io/arconia/latest/dev-services/lgtm/)
for the full property list.

!!! note "Development only"

    The gateway ships no OpenTelemetry export in production builds. The
    `observability` profile exists for the local development loop; wiring a
    deployed gateway to a collector is a separate, not-yet-implemented concern.

## UI development loop

Only needed when changing the portal:

```console
$ cd ui
$ corepack enable pnpm
$ pnpm install
$ pnpm dev          # proxies /api and /actuator to localhost:8080
$ pnpm test
$ pnpm storybook
```

## The gates

All must pass before any pull request:

```console
$ ./mvnw clean verify                     # Java + UI gates, packaged jar
$ (cd ui && pnpm e2e)                     # real-browser e2e vs a mock OIDC IdP
$ reqstool status local -p docs/reqstool  # must end "PASS"
$ openspec validate --all --strict
$ mkdocs build --strict                   # documentation
```

Use `clean` for the reqstool gate — incremental compilation truncates the
generated annotation files.

For the documentation gate:

```console
$ pip install -r docs/requirements.txt
$ mkdocs build --strict
```

## Next steps

- [Registering a marketplace](registering-a-marketplace.md)
- [Approving and rejecting snapshots](approving-snapshots.md)
- [Consuming approved skills](consuming-skills.md)
