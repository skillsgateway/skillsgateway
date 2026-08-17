# Evidence: publish-container-image

Final fresh run of all gates after the last code edit, on commit `ee90814`
(`feat(ci): publish the container image to GHCR by digest (#67)`), branch
`feat/publish-container-image` (stacked on `refactor/rename-maven-gav`).

## Fail-first proof (SVC_GW_0072)

`PackagingTests.releaseWorkflowCarriesThePublishByDigestContract` was written
and run against the pre-change workflow first:

```
[ERROR] dev.skillsgateway.server.PackagingTests.releaseWorkflowCarriesThePublishByDigestContract -- Time elapsed: 0.033 s <<< FAILURE!
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
```

After extending `native.yml`:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

## Gates

```
$ ./mvnw clean verify
[INFO] Tests run: 78, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ (cd src/main/frontend && pnpm e2e)
  8 passed (19.8s)

$ reqstool status local -p docs/reqstool
72/72 complete · 0 incomplete · PASS

$ openspec validate --all --strict
Totals: 19 passed, 0 failed (19 items)

$ mkdocs build --strict
INFO    -  Documentation built in 0.45 seconds
```

## Not verifiable locally

The push and attestation themselves only run in GitHub Actions on a push
event. The first `main` push after merge is the live verification: the job
summary must show the `ghcr.io/skillsgateway/skillsgateway@sha256:…` digest,
and `gh attestation verify oci://…@sha256:… --owner skillsgateway` must pass.
