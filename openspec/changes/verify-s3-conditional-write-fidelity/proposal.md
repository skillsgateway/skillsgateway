# Proposal: verify-s3-conditional-write-fidelity

## Why

GW_0112 — Reference transitions are the same on every storage backend — is the
requirement the object-store backend exists to satisfy, and its whole weight
rests on one behaviour of the store underneath: a conditional `PutObject` whose
`If-Match` precondition no longer holds must fail with 412 rather than
succeeding. `ConditionalWriteFidelityTests` proves that with five assertions.

**Until this change, every one of them had only ever run against Floci, a local
emulator.** That was task 2.3 of `pluggable-git-storage`, deliberately left open
when that change archived because it needed an AWS account the change did not
have, and moved to
[#151](https://github.com/skillsgateway/skillsgateway/issues/151) so it was
tracked rather than lost in an archived task list.

An emulator agreeing with itself is not evidence that the primitive is portable,
and this particular emulator has already been caught diverging. While the
concurrency suite was being written, Floci served a **partially written object
mid-overwrite** — a torn read carrying a matching `Content-Length` and its own
ETag, which real S3 does not do. The client carries two guards because of it.
The episode is the argument: the store the project tests against is not the
store it runs against, and it has already been shown to differ on the read path.

## What Changes

- **The assertions become store-agnostic.** They move into a shared
  `ConditionalWriteFidelitySuite`, so the emulator suite and the real-S3 suite
  are one contract rather than two copies that drift.
- **A real-S3 suite, credential-gated.**
  `RealS3ConditionalWriteFidelityTests` runs the same assertions against a real
  AWS S3 bucket. It is skipped — visibly, with a reason on every case — unless a
  bucket is named, so `mvnw clean verify` stays green with no AWS access and CI
  is unaffected. Bucket and region are the entire configuration surface; the
  client is built with no credentials provider, so the default AWS chain
  resolves them exactly as it does for the gateway.
- **The two mutation proofs become standing negative controls (M-A, M-B).** The
  spike established that the suite discriminates by hand-editing the
  preconditions out and re-running — a step easy to skip and impossible to audit
  afterwards. As tests, they re-establish it on every run against both stores: a
  store that refuses writes for an unrelated reason would answer 412 to
  everything and pass assertions 1–5 while proving nothing.
- **Two real-S3 specifics the emulator spike never exercised**, both named in
  #151: read-after-CAS from an independent client and connection, and a
  conditional PUT racing the DELETE that revocation performs.
- **A runbook**, `guides/verifying-an-object-store.md`, and the supported-store
  table updated to record what the run covered — and, as plainly, what it did
  not.

## Capabilities

### New Capabilities

_None._ No production code changes. Nothing the gateway does is different after
this change; what is different is that a claim the documentation was making on
inference is now made on evidence.

### Modified Capabilities

_None._ GW_0112's text is unchanged and its SVC is unchanged. `git-storage`
appears in this change's spec delta only to name the requirement whose
verification was extended.

## Evidence

The assertions ran against a real AWS S3 bucket on 2026-09-08:
**9 tests, 0 failures, 0 errors, 0 skipped**, first run, no retry. Both negative
controls passed as controls, which is what makes the other seven evidence rather
than vacuous. Cleanup was confirmed independently afterwards: the run-scoped
prefix was empty.

**The bucket was a plain bucket** — no versioning, no SSE-KMS. The
"ETag is not a content hash" case named in #151 is therefore **not covered**, and
both the runbook and the supported-store table say so rather than letting the
green run imply otherwise. No assertion ever computes an expected ETag, so
covering it needs no code change — only a run against a bucket configured that
way.

See `evidence.md`.

## Out of scope (named, so the boundary is explicit)

- **An endpoint override.** The suite targets AWS S3; an S3-compatible store on
  another endpoint cannot be pointed at it. The startup probe remains the answer
  for those, and the runbook says so.
- **A CI workflow that runs this on a schedule.** #151 suggests one. It needs a
  bucket, a role and a secret this project does not have, and a gate that can
  only ever skip is worse than a runbook that is actually run. The suite is
  built so such a workflow is a later addition of configuration, not of code.
- **ETag behaviour under SSE-KMS and bucket versioning**, per the evidence note
  above.

## Impact

- **DB**: none.
- **Backend**: none. Test sources only —
  `ConditionalWriteFidelitySuite` (new, shared),
  `ConditionalWriteFidelityTests` (reduced to the Floci wiring; no assertion
  changed, weakened or removed), `RealS3ConditionalWriteFidelityTests` (new).
- **API**: none.
- **Trust boundary**: none crossed. The suite exercises the store, not the
  gateway — no repository, no reference transition, no approval.
- **Requirements**: no new requirement and no new SVC. This change adds no
  required behaviour; it extends how an existing requirement's foundation is
  verified. Both fidelity suites carry no `@SVCs` for the same reason the
  original did — they verify the store, not the gateway — and a suite that
  skips by default could not satisfy an SVC in any case.
- **Docs** (same PR): new `guides/verifying-an-object-store.md`;
  `guides/storage-backends.md` supported-store table and its admonition;
  `mkdocs.yml` nav.
