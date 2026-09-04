# Evidence: collapse-vetting-content-reads

Tier 1 (no trust boundary crossed). Connectors still see only paths and bytes of
one pinned commit through the same interface; nothing on the approval path, the
facade or the ledger reads anything new. The one security-relevant edge is the
new per-run memory ceiling, which closes an exposure this change would otherwise
have opened — see "The bound is the security-relevant part" below.

Implementation commit: `3c6312bb8921badaca6c1829a51e963efba4da8a`.

## Spec ↔ test mapping

| Requirement | Verifies | Test |
| --- | --- | --- |
| GW_0162 — Bounded single-pass snapshot content access for a vetting chain run | unselected blob never opened; selected blob inflated once; identical blobs share one entry; oversize file still visited unread; content past the bound still served in full | `QuarantineSnapshotTests` (SVC_GW_0162), 6 tests |

"Never opened" is asserted the only way that cannot be faked: the blob is
**removed from the object database** before the snapshot is constructed, so an
implementation that materializes content the selection did not ask for fails to
find it and throws. `aBlobInsideTheSelectionIsStillRequired` is its counterpart
— the same missing blob, selected — proving the passing case is the selection
working and not the object still being readable. "Inflated once" is asserted by
array identity, which no re-read can produce.

## The tests fail against the old behaviour

The two properties were confirmed by reverting `QuarantineSnapshot` to what it
did before — materializing every blob and caching nothing — and re-running:

```
[ERROR] Tests run: 6, Failures: 2, Errors: 1, Skipped: 0
[ERROR]   QuarantineSnapshotTests.aBlobTwoConnectorsSelectIsInflatedOnce:92
[ERROR]   QuarantineSnapshotTests.identicalFilesShareOneCacheEntry:132
[ERROR]   QuarantineSnapshotTests.aBlobOutsideTheSelectionIsNeverOpened:63->walk:157
          » MissingObject Missing unknown 17c97a1a69376b860ab727947c339ec8ed2a1287
```

The three failures are exactly the three claims. The other three tests — the
oversize file, the retention bound, and the missing blob inside the selection —
pass under both implementations, which is the point: they are the assertions
that nothing about what a connector *sees* moved.

## Why no verdict can have changed

Each connector's visitor opened with the same path test that is now the
predicate:

| Connector | Was, inside the visitor | Is, as the selection |
| --- | --- | --- |
| `prompt-injection` | `if (!instructionContent(path)) return;` | `PromptInjectionConnector::instructionContent` |
| `license-scan` | `MANIFEST_PATH.equals(path)` / `LICENSE_FILE.matcher(path).find()` | `LicenseDetector::declaresLicense`, the same two constants |
| `secret-scan` | none — reads everything | none |
| external connectors | none — bundles everything | none |

Nothing else about the visit changed: a blob over `max-file-bytes` is still
visited with `null` content rather than omitted, so the `file-not-scanned`
findings behind `GW_0143 — A clean vetting pass records what it examined` and
`license-scan`'s explicit unknown for an oversized `LICENSE` are untouched. The
existing connector SVC tests are the standing evidence for that, and they pass
unchanged — none was modified, weakened or deleted.

## The bound is the security-relevant part

Reuse means holding content, and the content is an upstream repository the
gateway does not control. Unbounded, the cache is `O(snapshot)` for the length
of a run — a memory-exhaustion primitive reachable by anyone who can get a
marketplace registered. `skills-gateway.vetting.content-cache-bytes` (32 MiB)
bounds it, so peak goes from `O(largest file)` to `O(bound + largest file)`,
which is a number an operator sets. `contentBeyondTheRetentionBoundIsStillServedInFull`
runs with the bound at zero and asserts the content is still served correctly on
every walk: no value of the setting can cause a connector to see less.

## Gates — one fresh run after the last edit

```
$ ./mvnw clean verify
[INFO] Spotless.Java is keeping 274 files clean - 0 needs changes to be clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  06:23 min
```

Surefire across all reports: `Tests run: 496, Failures: 0, Errors: 0, Skipped: 0`.

```
$ (cd src/main/frontend && pnpm test:stories)
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

```
$ (cd src/main/frontend && E2E_GATEWAY_PORT=8091 pnpm e2e)
  13 passed (54.9s)
```

The default port 8081 was held by an unrelated process on this machine (an Expo
dev server belonging to other work), so the suite was run on 8091 —
`E2E_GATEWAY_PORT` is the runner's own knob for exactly this. All 13 specs ran
and passed.

```
$ reqstool status local -p docs/reqstool
  GW_0162             skills-gateway
155/155 complete · 0 incomplete · PASS
```

```
$ openspec validate --all --strict
Totals: 31 passed, 0 failed (31 items)
```

```
$ mkdocs build --strict
INFO    -  Documentation built in 1.71 seconds
```
