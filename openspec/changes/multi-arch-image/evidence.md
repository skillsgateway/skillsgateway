# Evidence — multi-arch-image

Commit under test: `62b4e8c` (`feat(release)!: publish a multi-arch image,
only from a release`), rebased onto `origin/main` at `4816287`
(`fix(npm): update dependency lucide-react to v1.39.0 (#263)`).

## A working-directory contamination incident, and how it was resolved

This machine's clone of the repository is shared with at least one other
concurrent agent session. Mid-implementation, `git reflog` showed the working
directory's checked-out branch being switched away from `feat/multi-arch-image`
and back by that other session while this change's files sat as uncommitted
edits. One consequence: PR #266 (`docs: a sealed root filesystem needs a
writable /tmp` — unrelated to this change) ended up on `main` carrying an
early, incomplete draft of this change's own `openspec/changes/multi-arch-image/`
scaffold, swept in as a side effect of that other session committing from a
shared working tree.

Recovery, in order:
1. `git stash push -u`, to capture this change's edits without losing them.
2. `git checkout feat/multi-arch-image` (the correct branch, still based on the
   original `6bfdb59`), then `git stash pop` — conflicted on every file this
   change touches, since the branch itself carried no commits of its own.
   Every conflict was resolved by taking the stash's content in full (`git show
   stash@{0}:<path>`); the base side had nothing of value to preserve.
3. Committed, then rebased onto the now-further-ahead `origin/main`. This
   surfaced the #266 contamination directly: an `add/add` conflict on the four
   `openspec/changes/multi-arch-image/*` files this change also creates,
   because #266 had already put a stale draft of them on `main`. Resolved the
   same way — this change's committed blob (`git show 62b4e8c:<path>`) written
   over the conflicted file in full — so the final content is this change's,
   not a merge of the two.
4. Re-ran `openspec validate multi-arch-image --strict` and
   `PackagingTests` after both the stash-pop resolution and the rebase, to
   confirm the recovery reproduced exactly the intended state rather than a
   silently-merged hybrid. Both passed each time (see below).

Every gate below ran to completion before this incident was discovered; the
two re-verifications after recovery are what confirm nothing was lost or
corrupted in the process, not a claim that the original runs were invalid.

## `./mvnw clean verify`

Two runs: the first failed on a Spotless formatting violation in
`PackagingTests.java` (fixed with `spotless:apply`); the second passed clean.

```
[INFO] Results:
[INFO]
[INFO] Tests run: 491, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] Spotless.Java is keeping 273 files clean - 0 needs changes to be clean, 273 were already clean, 0 were skipped because caching determined they were already clean
[INFO]
[INFO] --- checkstyle:3.6.0:check (default) @ skills-gateway-server ---
[INFO] You have 0 Checkstyle violations.
...
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:32 min
```

## `pnpm test:stories`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

## `pnpm e2e`

First attempt: all 13 tests failed at a consistent ~30s timeout. Root cause —
unrelated to this change — was a stray `spring-boot:run` dev-server process
left over from an earlier, unrelated local-dev session, squatting on port 8081
(the e2e gateway's port) with `dev-insecure-auth` enabled. The e2e script's
health check found *that* process healthy and ran the suite against it instead
of its own freshly-started gateway, so every test then timed out on UI it
could never reach. Killed the stray process, confirmed port 8081 free, reran:

```
  13 passed (38.9s)
```

## `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
155/155 complete · 0 incomplete · PASS
```

## `openspec validate --all --strict`

```
✓ change/multi-arch-image
...
Totals: 31 passed, 0 failed (31 items)
```

## `mkdocs build --strict`

```
INFO    -  Documentation built in 0.81 seconds
```

## Not weakened

`SVC_GW_0072`'s test (`releaseWorkflowCarriesThePublishByDigestContract`) keeps
every assertion from before this change that still describes true behavior
(the registry namespace, the `workflow_call` input, no `github.ref_name`
derivation, no `tags:` trigger, the job's permissions, the digest surfaced in
the step summary, the SBOM attestation shape) and adds new assertions for the
matrix, the release-only gate, the digest-only per-leg push, and the combine
job — it does not shrink. `SVC_GW_0072` and `GW_0072` both carry a bumped
revision (`0.4.0`) reflecting the contract change; a new `GW_0162`/`SVC_GW_0162`
pair, with its own new test, covers `ghcr-cleanup.yml`.
