# Tasks: revocation-freshness-is-not-a-knob

- [x] 1.1 Remove `Cache.refFreshness`; `ManifestStore.freshen()` always re-checks.
- [x] 1.2 Drop the now-unused last-checked timestamp.
- [x] 2.1 `RemovedProperties.ALL` gains the property as `REFUSE`.
- [x] 2.2 A guard test for it; mutation-test the disposition.
- [x] 3.1 Correct the three passages claiming a `10s` default; add the removal
      warning to the reference and the second removal to compatibility.
- [x] 4.1 Ratchet 105 → 104.
- [x] 4.2 Rename the cross-replica revocation test for the bound it now names;
      mutation-test that it still catches a cache that does not re-check.
- [x] 5.1 Gates and `evidence.md`.
