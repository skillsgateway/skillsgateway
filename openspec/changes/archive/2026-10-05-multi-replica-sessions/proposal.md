# Proposal: multi-replica-sessions

## Why

A trial ran 0.4.0 on the `object-store` backend with two replicas behind a load balancer without
session affinity (#594). Storage held up, and four things around it did not. The OAuth2
authorization request and the security context live in one replica's memory, so the identity
provider's callback lands on the other replica about half the time, and nobody can log in. The
failure logs nothing and reads "Invalid credentials". Which replica ran a background pass can only
be read from the database. And the object-store startup probe writes at the bucket root, which a
prefix-scoped grant refuses.

## What Changes

- Browser sessions move to the gateway's own PostgreSQL through Spring Session JDBC, with its two
  tables in `V1__init.sql`, so either replica answers either half of a login and every request after
  it. A stored session that can no longer be read, for example after an upgrade, counts as no
  session (a fresh login) rather than a 500. The session cookie is now named `SESSION`, not
  `JSESSIONID`.
- A failed browser sign-in is logged at WARN with the registration id and the OAuth2 error code, and
  is answered with a page that names the reason class (the sign-in was lost and should be started
  again, the identity provider declined it, or its answer could not be accepted) and links to start
  again. This replaces the generated "Invalid credentials" page.
- Each replica counts the background-pass turns it took and skipped:
  `skills_gateway.sweep.lease{pass, outcome=taken|skipped}`.
- The object-store startup probe writes its test object under the configured prefix.
- Docs: the multi-replica section of the storage guide (sessions, the lease counter, the IAM scope),
  the observability reference, the identity-providers guide, the session-cookie row of the
  configuration reference.

Stop rule: no `skills-gateway.*` leaf, no backend package, no estate object type, no role, no sweep
of ours. Spring Session runs its own expired-session cleanup, and that cleanup is idempotent on every
replica. `docs/manual/capability-map.md` absorbs all of it: the session store sits under Web
authentication, the counter under Observability, and the probe under Git storage.

## Capabilities

### New Capabilities

### Modified Capabilities

- `auth`: adds GW_AUTH_0053 — A browser session does not depend on which instance answers, and
  GW_AUTH_0054 — A failed browser sign-in is logged and names its reason.
- `observability`: adds GW_OBSERVABILITY_0005 — Each instance reports the background-pass turns it
  took and skipped.

## Impact

`pom.xml` (`spring-boot-starter-session-jdbc`), `application.yaml`, `V1__init.sql` (edited in place,
pre-1.0), `SecurityConfig` and a new failure handler in `auth`, `SweepLeases`,
`S3ObjectStoreClient`/`ObjectStoreClient.probe`, docs under `docs/manual/`, `docs/reqstool/`.
Existing sessions do not survive the upgrade, so users log in once more.
