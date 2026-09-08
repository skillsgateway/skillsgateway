# Verifying conditional writes against a real bucket

The object-store backend rests on one primitive: a conditional `PutObject` that
the store **refuses** when its precondition no longer holds. Everything else —
atomic reference transitions, more than one replica, publication that a
concurrent writer can neither lose nor half-observe — is a consequence of that
one behaviour. A store that accepts every write regardless of `If-Match` would
let a broken gateway pass its own concurrency suite: green, and meaningless.

So the gateway ships the assertions rather than the assurance. This guide runs
them against a real AWS S3 bucket, start to finish, and tears the bucket down
afterwards.

You do not need to do this to run the gateway. Do it when you want the evidence
first-hand — before a production cutover, after a store or account policy
changes, or because you would rather not take
[the supported-store table](storage-backends.md#which-object-stores-work) on
trust.

## What the suite asserts

Nine tests, in `RealS3ConditionalWriteFidelityTests`. Seven of them are shared
verbatim with the suite that runs against the local emulator on every build, so
the two stores are held to one contract rather than two.

| # | Assertion |
| --- | --- |
| 1 | `If-Match` with the current ETag succeeds and returns a **new** ETag |
| 2 | `If-Match` with a stale ETag returns **412** *and the stored object is byte-for-byte unchanged* |
| 3 | `If-None-Match: *` creates exactly once; the second attempt returns 412 and does not overwrite |
| 4 | 8 writers racing off one barrier from one base ETag → exactly **one** winner, **7** × 412, **zero** other errors |
| 5 | ETags chain: the ETag a conditional PUT returns is the one the next PUT must present |
| M-A | **Negative control.** With `If-Match` dropped, all 8 racing writers commit |
| M-B | **Negative control.** With `If-None-Match` dropped, the second create overwrites |
| 6 | A committed write is visible to an independent client on its own connection, at the ETag the winning PUT returned |
| 7 | A conditional PUT racing the DELETE that revocation performs is refused, and does not resurrect the object |

Assertion 2 checks the *content* after the refusal, not only the status code: a
store that answers 412 and writes anyway is the worst possible outcome, and a
status-only assertion is exactly what misses it.

!!! note "Why two of them are backwards"

    M-A and M-B are the reason the other seven mean anything. A store that
    refused writes for some unrelated reason — a policy, a lock, a quota —
    would answer 412 to everything and sail through assertions 1–5 while
    proving nothing at all. The controls drop the precondition and require the
    write to **land**. Only once both pass is a 412 elsewhere evidence that the
    precondition caused it.

    The emulator spike established this by hand-editing the preconditions out
    and re-running. Here they are standing tests, so the discrimination proof is
    re-established on every run instead of resting on a step someone remembered
    to do once.

## Before you start

- **A scratch bucket you are willing to delete.** The suite writes only under
  one run-scoped prefix and deletes what it wrote, but do not point it at a
  bucket holding anything you care about.
- **Credentials on the default AWS chain** — environment variables, an SSO
  profile, an instance role, whatever you normally use. The suite builds its
  client with no credentials provider of its own and never reads, stores or logs
  a credential.
- **A checkout and a JDK**, since this is a test in this repository rather than
  a shipped command.

The identity that runs it needs `s3:PutObject`, `s3:GetObject` and
`s3:DeleteObject` on `<bucket>/skills-gateway-fidelity/*`, and nothing else. It
never lists the bucket.

## 1. Create a scratch bucket

```bash
aws s3api create-bucket \
  --bucket <bucket> \
  --region <region> \
  --create-bucket-configuration LocationConstraint=<region>
```

!!! tip "A plain bucket answers the plain question; a configured one answers more"

    On a bucket with **SSE-KMS** or **versioning**, an ETag is not a content
    hash. That breaks any code that *computes* an expected ETag — and no
    assertion here computes one, they only chain the ETag the store returned. So
    pointing this suite at a bucket configured that way is itself the test for
    that case, at no extra effort:

    ```bash
    aws s3api put-bucket-versioning \
      --bucket <bucket> --versioning-configuration Status=Enabled

    aws s3api put-bucket-encryption --bucket <bucket> \
      --server-side-encryption-configuration \
      '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"aws:kms","KMSMasterKeyID":"<kms-key-id>"}}]}'
    ```

    Turning either on changes the teardown in [step 4](#4-tear-the-bucket-down)
    — versioning means deleting versions and delete markers, not objects.

## 2. Run the suite

```bash
./mvnw -o test-compile -DskipTests

export SKILLS_GATEWAY_FIDELITY_S3_BUCKET=<bucket>
export SKILLS_GATEWAY_FIDELITY_S3_REGION=<region>

./mvnw -o surefire:test \
  -Dtest=RealS3ConditionalWriteFidelityTests \
  -DfailIfNoSpecifiedTests=false
```

Those two variables are the entire configuration surface. The equivalent system
properties are `-Dskills-gateway.fidelity.s3.bucket` and
`-Dskills-gateway.fidelity.s3.region`; a system property wins where both are
set. Region is optional — without it the AWS SDK's own region chain decides.

The bucket name is the opt-in. **With no bucket named the suite skips**, which
is why `mvnw clean verify` is green on a machine with no AWS account and why CI
never needs one.

## 3. Read the result

A run that verified something says so:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

A run that verified nothing also says so, and cannot be mistaken for the first:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 9
```

Every skipped case carries the reason in
`target/surefire-reports/dev.skillsgateway.server.storage.RealS3ConditionalWriteFidelityTests.txt`.
Nine skips means the bucket variable did not reach the JVM — check that it is
exported, not merely set.

!!! danger "A failure here is not a test problem"

    These assertions do not exercise gateway code. If one fails, the store did
    not enforce a precondition it was asked to enforce, and the object-store
    backend has no correctness argument on it. Do not run the gateway against
    that store; there is no degraded mode, because last-writer-wins on the
    reference manifest is precisely the lost update the design exists to
    prevent.

    A failure of **M-A or M-B** is different and worse in its own way: the store
    refused a write that carried no precondition. The other assertions'
    412s then prove nothing, whatever they reported. Look for a bucket policy,
    an object lock or a quota before looking at the store.

## 4. Tear the bucket down

Each test deletes the keys it wrote, so a clean run leaves the prefix empty.
Confirm that before deleting anything:

```bash
aws s3api list-objects-v2 --bucket <bucket> --prefix skills-gateway-fidelity/
```

`KeyCount` should be `0`. Then:

=== "A plain bucket"

    ```bash
    aws s3 rm s3://<bucket>/skills-gateway-fidelity/ --recursive
    aws s3api delete-bucket --bucket <bucket> --region <region>
    ```

=== "A versioned bucket"

    Versioning means the objects are not gone, only marked. `s3 rm` adds delete
    markers rather than removing anything, and `delete-bucket` refuses while any
    version remains.

    ```bash
    aws s3api list-object-versions --bucket <bucket> \
      --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' \
      --output json > versions.json
    aws s3api delete-objects --bucket <bucket> --delete file://versions.json

    aws s3api list-object-versions --bucket <bucket> \
      --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}' \
      --output json > markers.json
    aws s3api delete-objects --bucket <bucket> --delete file://markers.json

    aws s3api delete-bucket --bucket <bucket> --region <region>
    ```

## What this does and does not settle

The suite verifies **the store's conditional-write semantics**. It does not
exercise the gateway: no repository, no reference transition, no approval. That
separation is deliberate — the point is to establish the primitive independently,
so that a green concurrency suite above it means something.

It is also not a substitute for the startup probe. The gateway probes the
configured bucket every time it starts and refuses to run where the probe fails,
so an unsupported store is a startup error rather than a corruption discovered
during an approval. This guide is how you find out *before* that, and in more
detail than a probe can.

Two limits worth naming:

- **The suite targets AWS S3.** There is no endpoint override, so an
  S3-compatible store on another endpoint cannot be pointed at it today; for
  those, the startup probe remains the answer.
- **Bucket configuration is yours to choose**, and the result only covers what
  you chose. A run against a plain bucket says nothing about SSE-KMS or
  versioning — see the tip in [step 1](#1-create-a-scratch-bucket).

## See also

- [Choosing and migrating the storage backend](storage-backends.md)
- [Configuration reference — Git storage](../reference/configuration.md#git-storage)
- [Trust boundaries](../concepts/trust-boundaries.md)
