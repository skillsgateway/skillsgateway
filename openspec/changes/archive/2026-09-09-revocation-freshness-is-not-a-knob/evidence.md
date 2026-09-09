# Evidence: revocation-freshness-is-not-a-knob

One fresh run of every gate after the last code edit.

Commit under test: `1aec59a` (rebased onto main after #342 and #343 merged; gates re-run)
Branch: `feat/revocation-freshness-is-not-a-knob`

## Gates

```
./mvnw clean verify                          → BUILD SUCCESS, 642 tests, 0 Checkstyle violations
(cd src/main/frontend && pnpm test:stories)  → 6 tests passed
(cd src/main/frontend && pnpm e2e)           → 13 passed (45.4s)
reqstool status local -p docs/reqstool       → 217/217 complete · 0 incomplete · PASS
openspec validate --all --strict             → 31 passed, 0 failed
mkdocs build --strict                        → built in 1.23s
```

The e2e run used `E2E_GATEWAY_PORT=18081`; 8081 was held by an unrelated process
on this machine. `run-e2e.sh` takes the port from that variable, so nothing about
the suite changed.

## What was found before the change

- **The default was `Duration.ZERO`.** `application.yaml` sets nothing, so the
  compact constructor's default was the deployed value everywhere.
- **No test varied it.** Every call site in
  `src/test/java/dev/skillsgateway/server/storage/objectstore/` passed
  `Duration.ZERO`. The non-zero branch of `ManifestStore.freshen()` was reachable
  only by configuration and covered by nothing.
- **The documentation stated a `10s` default in three places** — the reference
  table, the YAML sample and the storage guide. That default never existed in the
  code.

## Mutation results

| Mutation | Test that failed |
| --- | --- |
| The removal entry downgraded from `REFUSE` to `WARN` | `RemovedPropertyGuardTests.the_removed_revocation_freshness_knob_is_refused:111` |
| `freshen()` returns without re-reading the manifest — what a configured freshness used to cause | `ObjectStoreBackendTests.aRevokedSnapshotStopsBeingServedByAnotherReplicaOnTheNextAdvertisement:229` — "a warm cache must not be able to keep an unpublished snapshot on the wire" |

The second is the one that matters: it is the security property, it already
existed, and it fails against precisely the behaviour the removed knob could
buy.

## What the gates do not cover

- **The cost is not measured.** A replica now pays one conditional `GET` per
  reference advertisement with no way to amortise it. That was already the
  default, so nothing regressed — but no benchmark here says what that costs
  against a real bucket, and the escape hatch that would have bought it back is
  the thing being removed.
- **The object-store suite runs against the emulator, not real S3.** Unchanged by
  this and noted in `architecture.md` already; the conditional-`GET` semantics
  this now leans on harder are the emulator's here.
- **Nothing proves no other cache lengthens the window.** This closes the ref-map
  cache. JGit's `DfsBlockCache` holds pack *content*, which is immutable and
  content-named, so it cannot resurrect a reference — that is an argument from
  the design, not an assertion in a test.
- **An operator who sets the property is refused, not migrated.** There is no
  path that reads the old value and does something sensible with it, by choice:
  the sensible thing is zero, and zero is what they get by deleting the line.
