# Design: jgit-writable-config-home

## Context

Read from `org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r`'s own
sources (the pinned version in `pom.xml`), not remembered:

- `SystemReader.getXdgConfigDirectory(FS)`: reads `XDG_CONFIG_HOME` from the
  environment; if empty or unset, falls back to `$HOME/.config` via
  `fs.userHome()`; returns `null` only if both are unavailable.
- `SystemReader.Default.openJGitConfig(Config, FS)`: resolves the jgit config
  file at `<xdgConfigDirectory>/jgit/config`, or `<userHome>/.jgitconfig` if
  the XDG directory could not be determined at all. Confirms the two-step
  fallback the issue's "verify, don't assume" caveat calls out.
- `FS.FileStoreAttributes.getFileStoreAttributes(Path)` → `saveToConfig(...)`:
  called from `FS.FileStoreAttributeCache` after JGit measures a filesystem's
  timestamp resolution — the first time any repository under that filesystem
  is touched (opening a pack, taking a lock, etc.), asynchronously, on a
  background executor. `saveToConfig` calls
  `SystemReader.getInstance().getJGitConfig()`, which resolves through
  `openJGitConfig` above, and writes through a `LockFile` — the exact
  `LockFile: Creating lock file .../config.lock failed` /
  `Creating directories for .../.config/jgit failed` pair in the issue. This
  confirms the failing call path is the filesystem-attribute cache, not
  garbage collection (`gc.auto`, `gc.autoPackLimit` — issue #293's
  suggestion): a completely different area of the library, never on this
  call path.
- Measurement (and the save) is best-effort: `Files.isWritable(dir)` is
  checked for the *repository* directory before measuring at all, but the
  *config write* has no equivalent guard — it always attempts the write and
  only catches the resulting `IOException`, which is why the log line
  appears every time rather than once.

## Goals / Non-Goals

**Goals:**

- JGit resolves its own configuration cache to a path both the chart and
  plain-Docker deployments already make writable.
- No change to `readOnlyRootFilesystem: true` or any other part of the
  security posture.
- The fix is confirmed against the pinned JGit version's actual behaviour,
  not inferred from the stack trace alone.

**Non-Goals:**

- Silencing the log line by changing its level or suppressing the logger.
  That would hide a real (if rare) failure mode elsewhere — e.g. a
  misconfigured `XDG_CONFIG_HOME` pointing at something unwritable for a
  different reason — instead of removing the cause.
- Implementing #293's `gc.auto=0` / `gc.autoPackLimit=0` suggestion. Confirmed
  unrelated to this call path (see Context); left to #293, which is being
  closed by #310.
- Extending `native.yml`'s smoke test to exercise a fetch and assert the log
  line's absence end-to-end. See Risks below.

## Decisions

### Decision 1 — `XDG_CONFIG_HOME=/tmp/xdg-config`, set in both the image and the chart

The `Dockerfile` sets it as an image default (`ENV`), so plain-Docker and any
other orchestrator that just runs the image gets a working cache with no
configuration of its own. The chart's `templates/deployment.yaml` also states
it explicitly and unconditionally in the pod spec — not because the image
default is insufficient, but because every other piece of the read-only
posture (the `/tmp` mount itself, `SERVER_FORWARDHEADERSSTRATEGY`, the storage
backend) is stated in the rendered manifest rather than left to an operator
reading the image's own `ENV` lines, and an operator inspecting `kubectl get
pod -o yaml` should be able to see why the read-only posture doesn't break
JGit without pulling the image apart. A subdirectory of `/tmp`
(`/tmp/xdg-config`) rather than `/tmp` itself, so JGit's directory tree does
not share a level with whatever else lands in the shared scratch mount (the
`object-store` backend's pack cache, when persistence is `none`, also lives
under `/tmp` — see `SKILLSGATEWAY_STORAGE_OBJECTSTORE_CACHE_DIR`).

Alternatives considered:

- **Set only in the Dockerfile.** Sufficient on its own — a container's own
  `ENV` applies regardless of orchestrator, and Kubernetes container `env:`
  entries add to the image's environment rather than replacing it. Rejected
  as the sole fix anyway: the issue's suggested fix and this project's chart
  conventions (`SERVER_FORWARDHEADERSSTRATEGY`, the storage backend) both
  favour stating operationally-relevant environment explicitly in the
  rendered manifest, and duplicating a stable default costs nothing.
- **Set `HOME` instead of `XDG_CONFIG_HOME`.** Would also fix the immediate
  symptom (the fallback in `getXdgConfigDirectory` uses `fs.userHome()`), but
  `HOME` is a much broader lever — it affects anything else in the process
  that consults it — where `XDG_CONFIG_HOME` is scoped to exactly the
  standard it names. Rejected as broader than the fix needs.
- **A `values.yaml` key for the path.** Nothing about the path is meant to be
  operator-configurable — it is wiring between JGit's own convention and the
  `/tmp` mount that always exists, the same way the mount's `mountPath` itself
  is not a value. Rejected as a knob with no reason to turn it.

### Decision 2 — verify against the real library, then record it as evidence rather than a permanent test

The issue's own caveat — "confirm the message is actually gone rather than
assuming, since JGit's fallback chain has more than one step" — is answered by
running the pinned JGit version directly: `FS.getFileStoreAttributes(Path)`
against a probe directory, with `-Duser.home` pointed at a directory that
exists but carries no write permission (`chmod 555`), reproducing the exact
read-only-root-filesystem condition without a container. Before the fix
(`XDG_CONFIG_HOME` unset), both log lines from the issue appear verbatim.
After (`XDG_CONFIG_HOME` set to a writable directory, `user.home` left
read-only), no error is logged, and `$XDG_CONFIG_HOME/jgit/config` is written
with JGit's actual measured `timestampResolution`. Full transcripts in
`evidence.md`.

This is recorded as evidence rather than shipped as an automated test in the
main suite because the measurement path uses JGit's process-static caches
(`attrCacheByPath`, per-`FileStore` locks) and an async executor with a
best-effort wait — exactly the kind of global, timing-dependent state this
project's test suite avoids depending on (see `AbstractGatewayTest`'s isolated
git config). `PackagingTests` instead asserts the static, deterministic half:
that the `Dockerfile` and the rendered `Deployment` both carry
`XDG_CONFIG_HOME` under `/tmp` — matching the precedent set by
`forwarded-headers-native-image`, whose equivalent probe (task 2.2, "build the
native binary and probe every posture against it") is likewise evidence, not a
persisted test.

### Decision 3 — no extension to `native.yml`'s smoke test

The smoke test builds the container and asserts health and the OIDC redirect;
it performs no git fetch, so it cannot observe this defect today either way.
Making it do so would mean standing up an authenticated git client against
the facade inside CI — real scope, and one this change's collision note
already flags: issue #310 is rewriting the release pipeline (native image →
JVM container) on a parallel branch, and `native.yml` is exactly the kind of
file that rewrite is likely to replace or restructure. Adding scope there now
would widen an already-flagged merge conflict for no gain once #310 lands.
Left as a follow-up if #310's replacement pipeline gains git-fetch coverage.

## Risks / Trade-offs

- [`/tmp/xdg-config` collides with something else placed under `/tmp`] →
  Namespaced under its own subdirectory rather than at the mount root; the
  only other occupant today (`SKILLSGATEWAY_STORAGE_OBJECTSTORE_CACHE_DIR`,
  `/tmp/pack-cache`) is a sibling, not an ancestor or descendant.
- [The chart's env entry and the image's `ENV` drift to different paths on a
  future edit] → Both set to the literal `/tmp/xdg-config`; `PackagingTests`
  asserts the exact string in both files, so a drift fails the build rather
  than surfacing as a second, different noisy log line.
- [Issue #310 rewrites `Dockerfile` and this branch's one-line `ENV` conflicts]
  → Expected and called out in the proposal; kept to one line specifically so
  the resolution is trivial. This branch should rebase onto #310 once it
  merges.
- [A future JGit upgrade changes the fallback chain again] → The evidence
  transcript pins the exact library version probed
  (`7.7.1.202607240634-r`, matching `pom.xml`'s `jgit.version`), so a version
  bump that changes this behaviour is a decision for whoever bumps it, not a
  silent regression.

## Migration Plan

No data change, no API change. Rolling the image and chart forward is the
migration; a pod's environment gains one variable with no observable effect
other than the log line's disappearance. Rollback is rolling back; the log
line returns, harmlessly, exactly as it was before this change.
