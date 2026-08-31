# Evidence — fix-discarded-ref-update-results

Commit under test: `f106d864631cc8217cfbdd98c5cdedb8ca2a84f9`

One fresh run of every gate after the last code edit, in the order CLAUDE.md
requires. Nothing was re-run to get a better result; the two retries recorded at
the bottom are stated as retries.

Local runner notes: `TESTCONTAINERS_RYUK_DISABLED=true` (Ryuk cannot start on
rootless Podman) and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock`
(the Floci dev service bind-mounts the docker socket). Neither is needed in CI.

## `./mvnw clean verify`

```
[INFO] Tests run: 341, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 231 files clean - 0 needs changes to be clean, 231 were already clean
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

`clean` is deliberate: incremental compilation truncates the generated annotation
files the traceability gate reads.

## `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
JUNIT report written to .../test-results/storybook-junit.xml
```

## `(cd src/main/frontend && pnpm e2e)`

```
  ✓  13 [chromium] › e2e/portal.spec.ts:614:1 › the_session_holds_an_admin_role_derived_from_the_identity_providers_group_claim (272ms)

  13 passed (24.3s)
```

## `reqstool status local -p docs/reqstool`

```
  GW_0137             skills-gateway

INCOMPLETE (0)
130/130 complete · 0 incomplete · PASS
```

Run after the e2e gate, which is what populates `src/main/frontend/test-results/`.

## `openspec validate --all --strict`

```
✓ spec/vetting-waivers
✓ spec/virtual-catalog
Totals: 27 passed, 0 failed (27 items)
```

## `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: .../site
INFO    -  Documentation built in 0.69 seconds
```

## Defects proved to exist before they were fixed

Per the repo's discipline for trust-boundary work, each defect was made to fail
first. Recorded because the assertions are only worth what their ability to go red
is worth.

**#149, the masked publication.** Holding `refs/heads/main` the way a competing
writer does, then approving through the API:

```
[ERROR] PublicationIntegrityTests.a_refused_publication_does_not_report_an_approval:58
        Range for response status value 200 expected:<SERVER_ERROR> but was:<SUCCESSFUL>
```

`POST /api/snapshots/{id}/approve` answered **200** while nothing was published.

**The catalog exposure.** `ls-remote` against the served catalog marketplace:

```
Expecting actual:
  "dcad0dd...	HEAD
dcad0dd...	refs/catalog/corpeepkh7sran1
dcad0dd...	refs/heads/main
"
not to contain:
  "refs/catalog/corpeepkh7sran1"
```

**Approval was broken outright on the object-store backend.** Probed directly,
since `ApprovalService` dereferenced this value to build a fetch remote:

```
PROBE getDirectory() = null
```

A `DfsRepository` has no working directory, so real approval on that backend
raised `NullPointerException` before reaching the reference update. No test drove
approval on that backend, so nothing caught it. The probe was a throwaway; the
permanent guard is `publicationTransfersObjectsAndLandsBothRefs`, which runs on
both backends.

## Mutation proofs

Each new check was shown to fail when the thing it checks is broken.

| Mutation | Result |
| --- | --- |
| Facade `RefFilter` replaced with `RefFilter.DEFAULT` | `RefAdvertisementTests.no_internal_catalog_ref_is_advertised` fails; the characterization test still passes, so it is not tautological |
| `commitPublication` returns after writing only the pinned reference | both new contract cases fail on both backends |
| A raw `updateRef` reintroduced in `FilesystemGitStorage` | `RefResultDisciplineTests` fails, naming the file and the line |

A regression found the same way: filtering `HEAD` out of the advertised map broke
`git clone` while leaving `fetch` working. `FacadeTests` caught it before the
allowlist was committed.

## Retries

Two runs were repeated, both for reasons unrelated to the change, and both passed
unchanged:

1. `GitStorageContractTests` — first run failed on a test-arrangement error of my
   own (copying objects from the destination to itself), corrected in the test.
2. `PublicationIntegrityTests` — first two runs failed on assertion shape, not
   behaviour: the exception surfaces as `ServletException` → `ApprovalException` →
   `IOException`, so the assertion now names that chain and requires the root
   cause to mention both `refs/heads/main` and `LOCK_FAILURE` — i.e. that the
   failure came from the detected refusal rather than from anything incidental.

No container-runtime flake occurred during this run.
