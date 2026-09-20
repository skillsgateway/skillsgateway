# Evidence — role-enforcement-texts

One fresh run of every gate after the last edit.

**Commit:** `ff4502fe` — *fix(roles): the role texts describe enforcement as it actually works*

---

## The sweep (task 4.1)

The only verification a text-only change has, and the check whose absence let
`remove-roles-enabled-toggle` mark tasks 2.6 and 2.10 done without the edits
landing.

```console
$ grep -rniE "role enforcement is (enabled|on)|Enforcement is enabled|switch that defaults to off|disabled gateway|while disabled|flipping the switch|once enabled|whether or not role enforcement" docs/reqstool/ src/main/ docs/manual/ openspec/specs/
src/main/frontend/e2e/run-e2e.sh:103:# Role enforcement is ON and the only admin is the group the mock IdP puts in every token, so the
docs/reqstool/software_verification_cases.yml:2393:      THEN the disabled gateway pushes nothing and the mirror repository stays empty,
```

Both remaining hits are false positives of the regex, not of the defect:

- `run-e2e.sh:103` states a true fact about the e2e fixture; no conditional is implied.
- `software_verification_cases.yml:2393` is about a disabled **mirror**, not roles.

Three further hits for `skills-gateway.roles.enabled` are correct and deliberately
kept: `RemovedProperties`, `RemovedPropertyGuard` and `SecurityConfig` name the
property because they refuse startup when a deployment sets it, and
`compatibility.md` and `configuration.md` document its removal.

## Contract diff is descriptions only

```console
$ git diff -U0 src/main/frontend/openapi.json | grep "^[+-]" | grep -v "^[+-][+-]" | grep -vc '"description"'
0
```

Every changed line in the published OpenAPI document is a `"description"`. 16
descriptions changed; no path, schema, parameter or status code moved.

## Gates

```console
$ MAVEN_OPTS="-Xmx3g" ./mvnw clean verify
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  07:07 min
[INFO] Finished at: 2026-09-20T20:23:01+02:00

$ (cd src/main/frontend && pnpm test:stories)
 Test Files  9 passed (9)
      Tests  45 passed (45)
   Start at  20:24:47

$ (cd src/main/frontend && E2E_GATEWAY_PORT=8123 pnpm e2e)
  20 passed (1.1m)

$ reqstool status local -p docs/reqstool
248/248 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.34 seconds
```

`SVC_GW_AUTH_0010` remains covered by
`RoleEnforcementTests.a_no_role_session_is_refused_every_mutation_and_privileged_read_but_keeps_browsing_and_tokens`,
**unedited** — the revised GIVEN/WHEN/THEN matches what that test already did,
which is the check that the requirement was narrowed to fit the code rather than
the test loosened to fit the requirement.

## Two environment notes, neither a defect in this change

**Storybook needed three runs.** First: five suites failed to import
("Failed to fetch dynamically imported module") on a cold Vite cache after
`mvnw clean`. Second: one suite's iframe did not initialise within 60 s, with
17 GiB free. Third: 9 files, 45 tests, all passing. This is the known harness
flake, not a regression — no story or component is touched by this change.

**e2e ran on port 8123, not the default 8081.** Ports 8081 and 8099 were both
held by another project's dev servers belonging to a different session, so the
default was unavailable and killing them was not this change's business. The
harness takes `E2E_GATEWAY_PORT`; nothing in the repository was modified to
accommodate it.
