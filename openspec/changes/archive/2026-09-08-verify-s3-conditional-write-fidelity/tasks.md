# Tasks: verify-s3-conditional-write-fidelity

Verification-only change. No production code, no new requirement, no new SVC —
GW_FACADE_0011 — Reference transitions are the same on every storage backend is the
requirement whose foundation this proves, and its text and SVC are untouched.

## 1. Requirements (SSOT first)

- [x] 1.1 **No requirement added.** GW_0226 and GW_0227 were reserved for this
      change and are deliberately **not consumed** — they stay free. This change
      introduces no required behaviour; it extends the verification of GW_FACADE_0011.
      Confirmed against `docs/reqstool/requirements.yml` on this branch and
      against `origin/main`, whose highest id is GW_FACADE_0023
- [x] 1.2 **No SVC added.** Both fidelity suites carry no `@SVCs`, as the
      original did not: they verify the store, not the gateway. A suite that is
      skipped unless a bucket is configured could not satisfy one anyway —
      reqstool counts a passing surefire case, and the default build has none

## 2. Share the assertions rather than fork them

- [x] 2.1 Extract the five assertions and their helpers into
      `ConditionalWriteFidelitySuite`, an abstract base with `s3()`, `bucket()`,
      `keyPrefix()`, `prepareStore()` and `cleansUpAfterItself()` hooks
- [x] 2.2 Reduce `ConditionalWriteFidelityTests` to the Floci/Arconia dev-service
      wiring. No assertion changed, weakened or removed — verified by running it:
      `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`

## 3. The two mutation proofs become standing controls

- [x] 3.1 M-A: the 8-writer race with `If-Match` dropped must produce 8 winners
      and 0 refusals, or assertion 4's single winner is not evidence of
      compare-and-swap
- [x] 3.2 M-B: the second create with `If-None-Match` dropped must overwrite, or
      assertion 3's 412 proves nothing
- [x] 3.3 Both run against Floci on every build as well, so the discrimination
      proof stops depending on a hand-edit someone remembered to do once

## 4. The real-S3 suite

- [x] 4.1 `RealS3ConditionalWriteFidelityTests`, plain JUnit — no Spring context,
      no Docker, no dev service
- [x] 4.2 Gated on `SKILLS_GATEWAY_FIDELITY_S3_BUCKET` (or
      `skills-gateway.fidelity.s3.bucket`); region optional via the matching
      pair. Nothing else is read, and no credential is handled: the client is
      built with no credentials provider, so the default AWS chain applies
- [x] 4.3 Skipped **visibly** when unconfigured — 9 skips, each carrying the
      reason in the surefire report, so a build that verified nothing cannot be
      mistaken for one that did
- [x] 4.4 Prefix-scoped and self-cleaning: one run-scoped
      `skills-gateway-fidelity/<uuid>/` prefix, keys deleted by the key recorded
      when written, no `ListObjects` and no `ListBuckets` against the real store
- [x] 4.5 The two real-S3 specifics from #151: read-after-CAS from an independent
      client and connection, and a conditional PUT racing revocation's DELETE

## 5. Run it

- [x] 5.1 Run the suite against a real AWS S3 bucket under the owner's
      supervision. **9 tests, 0 failures, 0 errors, 0 skipped**, first run, no
      retry; both controls passed as controls; cleanup confirmed independently
- [x] 5.2 Record what the run did **not** cover: the bucket was plain, so ETag
      semantics under SSE-KMS and under versioning stay unverified

## 6. Documentation (same PR)

- [x] 6.1 New `guides/verifying-an-object-store.md` — the runbook: create a
      scratch bucket, run the suite, read the result, tear the bucket down.
      Generic placeholders throughout
- [x] 6.2 `guides/storage-backends.md`: the AWS S3 row becomes verified, and the
      warning becomes a note that states the plain-bucket limit rather than
      letting a green run imply more
- [x] 6.3 `mkdocs.yml` nav entry

## 7. Gates and evidence

- [x] 7.1 `evidence.md` — one fresh run of every gate after the last edit, with
      the real-S3 run recorded as a separate supervised run and its limits named
- [x] 7.2 Archive this change as the final commit of the PR
