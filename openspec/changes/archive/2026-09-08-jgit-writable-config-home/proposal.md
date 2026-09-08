# Proposal: jgit-writable-config-home

## Why

On every fetch, the log carries a caught (non-fatal) error:

```
ERROR LockFile: Creating lock file /home/nonroot/.config/jgit/config.lock failed
java.io.IOException: Creating directories for /home/nonroot/.config/jgit failed
```

JGit measures the local filesystem's timestamp resolution the first time it
touches a repository and caches the measurement in a user-level configuration
file, located via `$XDG_CONFIG_HOME/jgit/config`, falling back to
`$HOME/.config/jgit/config` when that variable is unset. The chart sets
`readOnlyRootFilesystem: true` (`helm/skills-gateway/values.yaml`), and neither
the chart nor the `Dockerfile` sets `XDG_CONFIG_HOME` or `HOME`. The image's
user is `nonroot` (uid 65532), so JGit resolves the path to
`/home/nonroot/.config/jgit` — on the sealed root filesystem, with nothing
mounted there. The write is caught and logged rather than thrown, so nothing
fails, but it is emitted on every fetch, drowning genuine errors, and JGit
never gets the working cache the write was for.

The read-only root filesystem is doing its job; the container simply has no
writable location JGit knows to use. One is already provided for exactly this
purpose — the chart's `/tmp` mount, `emptyDir` in Kubernetes, a tmpfs on plain
Docker — JGit just isn't told about it.

Issue [#294](https://github.com/skillsgateway/skillsgateway/issues/294).

## What Changes

- **`GW_FACADE_0024 — The embedded git library's own scratch stays inside the
  writable temporary directory`**, with SVC_GW_FACADE_0024. The `Dockerfile` sets
  `XDG_CONFIG_HOME=/tmp/xdg-config` as an image default; the chart's
  `templates/deployment.yaml` states the same variable explicitly in the pod
  spec, so it stays correct even against an image that does not set it.
- **No change to the security posture.** `readOnlyRootFilesystem: true` is
  unchanged; the fix only tells JGit about scratch space the chart already
  provides.
- **Verified against the real library, not assumed**: a standalone probe
  against `org.eclipse.jgit:7.7.1.202607240634-r` reproduces the exact log
  lines under a read-only `user.home`, and confirms they disappear — and that
  JGit successfully writes its cache to `$XDG_CONFIG_HOME/jgit/config` — once
  the variable points at a writable directory. Transcript in `evidence.md`.
  This matters because JGit's fallback chain has more than one step
  (`XDG_CONFIG_HOME`, then `$HOME/.config`, then giving up and returning a
  fallback measurement) and the fix has to close the one the deployment
  actually exercises.
- **Not #293's `gc.auto` suggestion.** The call path that fails
  (`FS.FileStoreAttributes.saveToConfig`, reached from the same library's
  filesystem-timestamp-resolution measurement) is unrelated to garbage
  collection; read from JGit's own sources to confirm before ruling it out
  rather than assuming. Not implemented here.
- **Docs corrected** in the same PR: `guides/deploying-on-kubernetes.md`,
  `guides/deploying-without-kubernetes.md`, `reference/configuration.md`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

_None functionally._ `release-packaging` gains GW_FACADE_0024 — a packaging
correctness requirement, verified by a static consistency check
(`PackagingTests`) rather than a change to any served capability's behaviour.

## Impact

- **DB**: none.
- **Backend**: none — no application code path changes; this is packaging
  configuration only.
- **API**: none.
- **Portal**: none.
- **Chart / image**: `Dockerfile` gains one `ENV` line;
  `helm/skills-gateway/templates/deployment.yaml` gains one unconditional
  environment entry. No new `values.yaml` key — the path is an internal
  wiring detail tied to the `/tmp` mount that already exists unconditionally,
  the same way the mount path itself is not exposed as a value.
- **CI**: none. `native.yml`'s smoke test does not exercise a git fetch today,
  so it cannot observe this defect either before or after the fix; extending
  it to do so is out of scope here (see Risks in design.md) and is also the
  workflow issue [#310](https://github.com/skillsgateway/skillsgateway/issues/310)
  is actively rewriting in parallel.
- **Docs**: the three files listed above.
- **Trust boundary**: none touched. This does not change authentication,
  authorization, or what the facade serves — only where an embedded library
  writes a cache file that no request depends on.
- **Declarative estate obligation**: nothing to add. No API-managed runtime
  state and no grantable role is introduced.
- **Known collision**: issue #310 (native image → JVM container, ADR 0012) is
  being implemented in parallel on branch `feat/jvm-container-release`, which
  is very likely rewriting this same `Dockerfile` wholesale. This change keeps
  its `Dockerfile` edit to the single `ENV` line so the conflict is trivial to
  resolve; #310 should merge first and this branch should rebase onto it.
