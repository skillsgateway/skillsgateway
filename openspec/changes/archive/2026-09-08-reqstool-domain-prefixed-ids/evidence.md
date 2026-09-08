# Evidence: reqstool-domain-prefixed-ids

One fresh run of every gate against commit `14b1f0f54d7b82f99167e6770d8ff820d53f4bd9`
(the rename commit; the evidence-file and archive commits that follow it change
nothing this evidence covers).

## Environment note

Local runs use Podman (rootless). Two exports were required, per
`docs/manual/guides/local-development.md#running-the-gates-on-podman`:

```console
$ export TESTCONTAINERS_RYUK_DISABLED=true
$ export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
```

`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` was not already set in this
environment; without it, every container-backed test failed identically with
`operation not permitted ... lsetxattr ... /var/run/docker.sock` (370 errors,
0 real assertion failures — every count was `Errors`, raised before a test
body ran) — a rootless-Podman socket-path problem, not a regression from this
change. Setting it and re-running produced the results below with no other
change.

Playwright's Chromium binary was also missing locally
(`chromium_headless_shell-1243`) and was installed with
`pnpm exec playwright install chromium` before the e2e run below.

## Gate results

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | **BUILD SUCCESS** — `Tests run: 619, Failures: 0, Errors: 0, Skipped: 9` |
| `pnpm test:stories` | **PASS** — `Test Files 3 passed (3)`, `Tests 6 passed (6)` |
| `pnpm e2e` | **PASS** — `13 passed (55.5s)` |
| `reqstool status local -p docs/reqstool` | **`180/180 complete · 0 incomplete · PASS`** |
| `openspec validate --all --strict` | **PASS** — `Totals: 36 passed, 0 failed (36 items)` |
| `mkdocs build --strict` | **PASS** — exit 0 |

### `./mvnw clean verify` — tail

```
[INFO] Results:
[INFO]
[INFO] Tests run: 619, Failures: 0, Errors: 0, Skipped: 9
[INFO]
...
[INFO] --- spotless:3.10.2:check (default) @ skills-gateway-server ---
[INFO] Spotless.Java is keeping 323 files clean - 0 needs changes to be clean, 323 were already clean, 0 were skipped
[INFO] --- checkstyle:3.6.0:check (default) @ skills-gateway-server ---
[INFO] You have 0 Checkstyle violations.
[INFO] --- reqstool:1.1.0:assemble-and-attach-zip-artifact (default) @ skills-gateway-server ---
[INFO] Assembled zip artifact: .../target/reqstool/skills-gateway-server-0.3.0-62-SNAPSHOT-reqstool.zip
[INFO] BUILD SUCCESS
[INFO] Total time:  03:36 min
```

One retry was needed within this run, disclosed rather than hidden: the
first `mvnw clean verify` attempt failed Spotless on two files
(`ResolutionBudget.java`, `FetchLogRepository.java`) whose `@Requirements`/
`@SVCs` annotation lines and one Javadoc line exceeded the formatter's line
length once the domain-prefixed ids replaced the shorter flat ones.
`mvnw spotless:apply` reformatted both (list-per-line for the annotation,
wrapped Javadoc), and the immediate re-run was clean — no test logic changed.

### `pnpm test:stories` — tail

```
✓ |storybook (chromium)| src/pages/tokens.stories.tsx (1 test) 220ms
✓ |storybook (chromium)| src/components/markdown-view.stories.tsx (2 tests) 405ms
  ✓ Skill  380ms
✓ |storybook (chromium)| src/pages/adoption.stories.tsx (3 tests) 216ms

Test Files  3 passed (3)
     Tests  6 passed (6)
```

### `pnpm e2e` — tail

```
Running 13 tests using 1 worker
  ✓ 1..13 [chromium] › e2e/portal.spec.ts:* ...
  13 passed (55.5s)
```

### `reqstool status local -p docs/reqstool` — tail

```
INCOMPLETE (0)
180/180 complete · 0 incomplete · PASS
```

180 requirements, 180 SVCs (172 top-level + 8 dot-notation children under
`GW_INGEST_0026`), every one covered by at least one passing automated test
result. Before the e2e run, 14 SVCs whose only test coverage is
`e2e/portal.spec.ts` (Playwright) showed `automated test missing` — expected,
since `pnpm e2e` had not yet produced `test-results/playwright-junit.xml` in
this fresh run; resolved once that gate ran.

### `openspec validate --all --strict` — tail

```
✓ change/reqstool-domain-prefixed-ids
...
Totals: 36 passed, 0 failed (36 items)
```

### `mkdocs build --strict` — tail

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.85 seconds
```
(exit 0; the only console output otherwise is an upstream Material-for-MkDocs
2.0 deprecation notice, unrelated to this repository.)

## Beyond the gates

- `docs/reqstool/requirements.yml` / `software_verification_cases.yml`: 180
  requirements, 180 SVCs, zero duplicate ids in either file, every new id
  matches `GW_<DOMAIN>_NNNN(.N)?`.
- `git grep -oE "GW_[0-9]{4}\b"` on the final tree matches only ids that were
  never entered in `requirements.yml`: `GW_0074`, `GW_0113`, `GW_0119`,
  `GW_0123`, `GW_0124` (reserved-then-abandoned gaps) and provisional ids
  named in four active, unimplemented OpenSpec change proposals plus the
  not-yet-accepted ADR 0016 — see `proposal.md`'s "Deliberately not renamed"
  list. No real id was left in its old flat form.
- No open PR existed against `docs/reqstool/` at proposal time
  (`gh pr list --repo skillsgateway/skillsgateway --state open` returned
  none), so the wholesale rename carries no known merge-collision risk as
  proposed. Re-checked immediately before this PR is opened.
