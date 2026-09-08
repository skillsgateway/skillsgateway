# Evidence: jgit-writable-config-home

Commit under test: `112f387a33207ced025b36a3482782d0e57d7875` (final code commit,
`fix(chart): point JGit's own config cache at the writable /tmp mount`).

## 1. JGit-level confirmation the log line is gone (not assumed)

The issue's own caveat — "confirm the message is actually gone rather than
assuming, since JGit's fallback chain has more than one step" — is answered
directly against the pinned library, `org.eclipse.jgit:org.eclipse.jgit:
7.7.1.202607240634-r` (the exact version in `pom.xml`'s `jgit.version`),
rather than by reading the source alone.

Read from that version's own sources first (`SystemReader.java`, `FS.java`):
`SystemReader.getXdgConfigDirectory(FS)` reads `XDG_CONFIG_HOME`; if empty,
falls back to `fs.userHome()/.config`. `SystemReader.Default.openJGitConfig`
resolves `<xdgConfigDirectory>/jgit/config`. `FS.FileStoreAttributes.get(Path)`
measures a filesystem's timestamp resolution the first time a repository
under it is touched, and asynchronously calls `saveToConfig`, which resolves
through `getJGitConfig()` → `openJGitConfig` above and writes through a
`LockFile` — the same class and the same two log lines the issue reports.
This confirmed the failing call path is JGit's filesystem-attribute cache,
**not** garbage collection (`gc.auto`, `gc.autoPackLimit` — issue #293's
suggestion, a different area of the library entirely, never reached from
here).

Then reproduced directly with a standalone probe (`Probe.java`, compiled and
run against the pinned jar plus `slf4j-simple` for visible logging):

```java
Path probeDir = Files.createTempDirectory("jgit-probe-repo");
FileStoreAttributes attrs = FS.getFileStoreAttributes(probeDir);
Thread.sleep(3000); // let the async save-to-config executor run
```

**Before** — `user.home` pointed at a directory that exists but carries no
write permission (`chmod 555`), simulating the read-only root filesystem
without needing a container; `XDG_CONFIG_HOME` unset:

```
$ env -u XDG_CONFIG_HOME java -Duser.home=$PWD/readonly-home -cp ".:$CP" Probe
=== user.home=.../readonly-home XDG_CONFIG_HOME=null ===
measured resolution: PT0.000002S
[JGit-FileStoreAttributeWriter-1] ERROR org.eclipse.jgit.internal.storage.file.LockFile - Creating lock file .../readonly-home/.config/jgit/config.lock failed
java.io.IOException: Creating directories for .../readonly-home/.config/jgit failed
	at org.eclipse.jgit.util.FileUtils.mkdirs(FileUtils.java:421)
	at org.eclipse.jgit.internal.storage.file.LockFile.createLockFile(LockFile.java:172)
	at org.eclipse.jgit.internal.storage.file.LockFile.createLockFileWithRetry(LockFile.java:165)
	at org.eclipse.jgit.internal.storage.file.LockFile.lock(LockFile.java:146)
	at org.eclipse.jgit.storage.file.FileBasedConfig.save(FileBasedConfig.java:201)
	at org.eclipse.jgit.util.FS$FileStoreAttributes.saveToConfig(FS.java:761)
	at org.eclipse.jgit.util.FS$FileStoreAttributes.lambda$4(FS.java:433)
	...
[JGit-FileStoreAttributeWriter-1] ERROR org.eclipse.jgit.util.FS - Cannot save config file 'FileBasedConfig[.../readonly-home/.config/jgit/config]'
java.io.IOException: Creating directories for .../readonly-home/.config/jgit failed
	...
=== done ===
```

Verbatim match of both log lines and the exception in the issue.

**After** — same read-only `user.home`, `XDG_CONFIG_HOME` now set to a
writable directory (mirroring the fix's `/tmp/xdg-config`):

```
$ XDG_CONFIG_HOME=$PWD/xdg-writable java -Duser.home=$PWD/readonly-home -cp ".:$CP" Probe
=== user.home=.../readonly-home XDG_CONFIG_HOME=.../xdg-writable ===
measured resolution: PT0.000003S
=== done ===

$ find xdg-writable -type f
xdg-writable/jgit/config

$ cat xdg-writable/jgit/config
[filesystem "Eclipse Adoptium|25.0.2|/dev/disk3s5"]
	timestampResolution = 3000 nanoseconds
	minRacyThreshold = 0 nanoseconds
```

No error logged, and JGit's cache was actually written to
`$XDG_CONFIG_HOME/jgit/config` — the working cache the original write was for,
not merely a silenced failure.

## 2. Chart rendering

```
$ helm lint helm/skills-gateway
1 chart(s) linted, 0 chart(s) failed

$ helm template test helm/skills-gateway --set postgresql.host=db.example.com \
    --set postgresql.existingSecret=db-secret --set oidc.existingSecret=oidc-secret \
    --set persistence.mode=ephemeral | grep -A2 XDG_CONFIG_HOME
            - name: XDG_CONFIG_HOME
              value: /tmp/xdg-config
```

## 3. `./mvnw clean verify`

Java 25, GraalVM native profile not invoked (packaged-jar path per ADR 0012).
This machine runs many other agents' worktrees concurrently against the same
Docker daemon; two spurious failures occurred before a clean run, both
matching the project's documented container-runtime-blip signature
(`README`/brief: "A container-runtime blip ... is real on this machine under
parallel load and passes unchanged on retry") — recorded here rather than
hidden:

- **Attempt 1** failed on a genuine defect in this change: a Spotless
  formatting violation in the new `PackagingTests` method (a line-wrapped
  `Files.readString` call Spotless wanted on one line). Fixed; `./mvnw -q
  spotless:check` confirmed clean afterwards.
- **Attempts 2–5** (after the Spotless fix) each failed with `Tests run: ...,
  Errors: 16/68/73/27` — every failing test in every attempt traced to
  `com.github.dockerjava.zerodep...NoHttpResponseException: localhost:2375
  failed to respond` / `ContainerLaunchException: Container startup failed
  for image postgres:18.4-alpine`, i.e. the shared Docker daemon rejecting or
  timing out connections under concurrent load from other agents' parallel
  Testcontainers-backed suites (observed directly: `docker ps -q | wc -l`
  climbed from 17 to 65 running containers across these attempts, all
  `postgres:18.4-alpine`/`floci:1.6.0` pairs from other worktrees' test runs,
  none stale — ages under a few minutes each, still churning). The set of
  failing test classes was **different and non-overlapping between
  attempts** (confirmed by diff), which is the signature of resource
  contention, not a deterministic defect; `PackagingTests` (this change's own
  test) passed in every attempt. None of the failures touched code this
  change added or modified.
- Coordinated with the other concurrently-running agents via a shared
  `gate-lock.sh` mutex (`scratchpad/gate-lock.sh`, `mkdir`-based, provided by
  the orchestrator) so only one agent's container-heavy Maven/e2e run
  executes at a time on this machine; acquired as `issue-294`, released
  unconditionally after the run.

**Final run, under a shared cross-agent mutex (`gate-lock.sh`, later replaced
mid-session by a fairer FIFO `gate-queue.sh` — both guard the same lock
directory), clean:**

```
$ "$SP/gate-lock.sh" acquire issue-294 \
    && ( cd "$WT" && ./mvnw clean verify ) > "$WT/gate-verify.log" 2>&1
$ "$SP/gate-lock.sh" release issue-294

[INFO] Tests run: 587, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:33 min
```

Once contention was serialized against other agents' own container-heavy
suites, the run was clean on the first try and dramatically faster (2:33 min
vs. ~17 min per prior contended attempt) — corroborating that attempts 2–5
above were genuinely external contention, not a defect in this change.
`dev.skillsgateway.server.PackagingTests` (10 tests, including the new
`imageAndChartPointJGitsOwnConfigCacheAtTheWritableTemporaryDirectory`
covering SVC_GW_FACADE_0024) passed.

## 4. `pnpm test:stories`

```
$ pnpm test:stories
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## 5. `pnpm e2e`

Run from `src/main/frontend`, queued behind other agents via the same shared
mutex (`gate-queue.sh`, the FIFO replacement):

```
$ (cd src/main/frontend && pnpm e2e)
waiting for gateway health...

Running 13 tests using 1 worker

  ✓  1..13 [chromium] › e2e/portal.spec.ts ...

  13 passed (42.3s)
```

(An earlier attempt in this session invoked `pnpm --dir src/main/frontend e2e`
from the worktree root and failed on a corepack pnpm-version mismatch — "This
project is configured to use 12.3.4 of pnpm. Your current pnpm is v11.25.0" —
an invocation-context issue, not a test failure; re-run by `cd`-ing into
`src/main/frontend` first, matching this project's documented gate command,
and it passed cleanly.)

## 6. `reqstool status local -p docs/reqstool`

Run after `pnpm e2e` in the same working tree (reqstool reads
`src/main/frontend/test-results/`), with `clean` having been used for the
Maven build above so annotation files were not truncated by incremental
compilation:

```
$ reqstool status local -p docs/reqstool
  ...
  GW_FACADE_0024             skills-gateway
  ...

INCOMPLETE (0)
166/166 complete · 0 incomplete · PASS
```

Last line: `166/166 complete · 0 incomplete · PASS`. GW_FACADE_0024 and its SVC are
present and complete.

## 7. `openspec validate --all --strict`

```
$ openspec validate --all --strict
✓ change/jgit-writable-config-home
...
Totals: 32 passed, 0 failed (32 items)
```

## 8. `mkdocs build --strict`

```
$ mkdocs build --strict
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.89 seconds
```
Exit 0; only Material for MkDocs' own upstream-deprecation banner printed
(unrelated to this change, a warning about MkDocs 2.0 from the theme itself).

## Summary

All gates green on the final run at commit `112f387a33207ced025b36a3482782d0e57d7875`:

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | BUILD SUCCESS, 587/587 tests, 0 failures/errors |
| `pnpm test:stories` | 3 files / 6 tests passed |
| `pnpm e2e` | 13/13 passed |
| `reqstool status local -p docs/reqstool` | `166/166 complete · 0 incomplete · PASS` |
| `openspec validate --all --strict` | 32/32 passed |
| `mkdocs build --strict` | exit 0 |

Retries recorded: one genuine fix (a Spotless formatting violation in the new
`PackagingTests` method, attempt 1), then four attempts (2–5) lost to
external Docker/Testcontainers contention from other agents' concurrent
suites on this shared machine (`localhost:2375 failed to respond` /
`Container startup failed for image postgres:18.4-alpine`; a different,
non-overlapping set of failing test classes each time; `docker ps -q | wc -l`
observed climbing to 65 concurrently-running containers). Resolved not by
reducing scope but by coordinating with the other agents through a shared
mutex so only one container-heavy suite runs at a time on the shared Docker
daemon; the run under that mutex was clean on the first try.

The JGit-level fix itself was confirmed empirically against the pinned
library version (§1), not inferred from source alone: the exact log lines
from the issue reproduce under a simulated read-only home, and disappear —
with the cache actually written — once `XDG_CONFIG_HOME` points at a
writable directory.
