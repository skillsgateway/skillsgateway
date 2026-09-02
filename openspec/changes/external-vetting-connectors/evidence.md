# Evidence — external-vetting-connectors

Trust-boundary change (vetting chain). old-coder Tier 3. **Spec approval: not
obtained (autonomous run)** — confidence claimed correspondingly lower; the spec
(`spec-approval.md`) is the artifact the owner reviews after the fact.

## Source state

- Branch `feat/external-vetting-connectors`; see the final commit SHA in the PR.
- New main sources: `ExternalConnectorProperties`, `ExternalVetRequest`,
  `ExternalVetResponse`, `ExternalVettingConnector`,
  `ExternalVettingConnectorRegistrar`, `ExternalVettingConfiguration` (package
  `dev.skillsgateway.server.vetting`). No existing vetting class changed.

## Environment note (why some gates are deferred)

Every `@SpringBootTest` in this project loads the S3 autoconfiguration, which
starts the Arconia **Floci** dev-service container (`floci/floci:1.6.0`). On this
shared podman VM that container fails to start:

```
InternalServerErrorException: Status 500: failed to change selinux label:
insufficient permissions ... lsetxattr(...) /var/run/docker.sock: operation not permitted
```

This is environmental and pre-existing (it blocks the whole integration suite,
not this change); retried once with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` set,
same failure. So the container-backed integration tests, `./mvnw clean verify`
and the reqstool status gate are **deferred to the serial pre-merge run**. The
fail-closed trust boundary is fully covered by the container-free unit layer
below, so the deferral does not leave the critical behaviour unverified.

## Gauntlet — ran here

### Compile (main + test), clean

```
./mvnw -q clean
./mvnw -q -Dskip.ui.verify=true compiler:compile compiler:testCompile
→ clean, no errors
```

### Spotless (palantir-java-format)

```
./mvnw -q -Dskip.ui.verify=true spotless:apply   → applied, files reformatted
```

### Unit suite (container-free, the fail-closed layer) — final fresh run

`ExternalVettingConnectorUnitTests` drives every fail-closed branch against a real
in-process JDK `HttpServer`:

```
./mvnw -q -Dskip.ui.verify=true surefire:test -Dtest=ExternalVettingConnectorUnitTests
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
```

Covers (→ SVC): pass/warn/info mapping, worst-of, credential header, binary
shipped-unscanned (SVC_GW_0144, SVC_GW_0146); `pending` blocks (SVC_GW_0147); and
the fail-closed matrix (SVC_GW_0145) — unreachable, non-2xx, read timeout, empty,
JSON-null, unparseable, unknown state, missing state, declared `error`, malformed
finding severity, missing finding id, oversized response, over-cap request bundle.

### Manual mutation (the unit suite is non-vacuous)

Three load-bearing branches mutated one at a time, suite re-run, mutant killed,
source restored (`diff` vs backup: IDENTICAL after restore):

| Mutant | Expected killer | Result |
| --- | --- | --- |
| non-2xx branch → `Verdict.pass()` | `aNon2xxStatusIsAnErrorVerdict` | Tests run: 1, Failures: 1 — killed |
| unknown/missing state → default `PASS` | 3 unknown/missing/`error`-state tests | Tests run: 3, Failures: 3 — killed |
| drop worst-of (`effective = declared`) | `aPassAlongsideACriticalFindingIsRecordedAsFailByWorstOf` | Tests run: 1, Failures: 1 — killed |

### YAML well-formedness (reqstool SSOT)

```
ruamel.yaml + PyYAML load of requirements.yml and software_verification_cases.yml → OK
```

Generated annotations confirm the mapping is captured:

```
target/generated-sources/annotations/resources/annotations.yml:      GW_0144..GW_0147
target/generated-test-sources/test-annotations/resources/annotations.yml: SVC_GW_0144..SVC_GW_0147
```

### OpenSpec

```
openspec validate external-vetting-connectors --strict → valid
openspec validate --all --strict → Totals: 27 passed, 0 failed
```

### Docs

```
mkdocs build --strict → Documentation built (no warnings/broken links)
```

## Gauntlet — deferred to serial pre-merge (Floci/podman blocker above)

- `ExternalVettingConnectorTests` and `ExternalConnectorRegistrationTests`
  (`@SpringBootTest`): compiled clean; they verify persistence of the external
  verdict/report-link, chain ordering, and that a blocked snapshot stays held and
  unapprovable end to end. To run: `./mvnw -Dtest='ExternalVettingConnector*Tests,ExternalConnectorRegistrationTests' test`
  in an environment where the Floci dev service starts.
- `./mvnw clean verify` (full Java + UI + jar).
- `reqstool status local -p docs/reqstool` — expected `N/N complete · PASS` once
  the SVC junit results from the integration tests exist.
- Frontend story/e2e suites: not touched by this change (no portal change here;
  portal surfacing is #224).

## Known limits (declared)

- The inbound asynchronous **resolution callback** is out of scope; a `pending`
  answer blocks (fail-closed) until such a mechanism ships.
- Endpoint auth beyond a bearer/credential header (mTLS, request signing) is a
  decision to confirm (see `design.md` open questions and ADR 0009).
