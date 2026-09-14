# Evidence: container-smoke-test-ingests-and-clones

Commit under test: `0187238`, branched from `1ed8125` (`main`).

The change touches one file, `.github/workflows/native.yml`, and that file is not
exercised by any local gate — so the evidence that matters here is a real run of
the workflow, in both directions.

## The workflow, run on the branch

`gh workflow run native.yml --ref test/container-smoke-test-ingests-and-clones`
→ [run 34886657185](https://github.com/skillsgateway/skillsgateway/actions/runs/34886657185),
**success**. (Re-dispatched after rebasing onto `1ed8125`; the first run,
[34815036574](https://github.com/skillsgateway/skillsgateway/actions/runs/34815036574),
was the same result on the pre-rebase base. `native.yml` is byte-identical in
both — the only later commit on this branch edits this file.)

```
Container build & smoke test: success
   8. Build the marketplace fixture: success
   9. Build container image: success
  10. Smoke test container: success
  11. Smoke test the product on the published image: success
  12. Helm lint: success
  13. Log in to GHCR: skipped
  14. Push the multi-arch image: skipped
  15. Attest SBOM: skipped
```

The three skipped steps are the publication path, which runs only from
`release.yml` with `inputs.version` set. A dispatch builds and smoke-tests
exactly what a release does and publishes nothing, which is the existing design
and is unchanged.

The new step's own output:

```
SMOKE_FIXTURE: /home/runner/work/_temp/smoke-fixture
the published image ingested, approved, published and served a marketplace
```

## That it fails when the product is broken

A test that cannot fail is what this change exists to replace, so the negative
case was run rather than argued. Locally, against the same image built from the
Dockerfile, with the fixture **not** bind-mounted so ingestion has nothing to
fetch:

```
--- health says UP (the OLD smoke test would pass here) ---
{"groups":["liveness","readiness"],"status":"UP"}
--- new step: ---
smoke: ingest produced state 'null', expected held: {"detail":"ingestion failed for
marketplace 'smoke'","instance":"/api/marketplaces/smoke/ingest","status":502,"title":"Bad Gateway"}
EXIT=1
```

That is #293's shape exactly: the image boots, reports healthy, and cannot
ingest. The old assertion passes; the new one exits non-zero and names the
failure.

## Local run of the positive case

Before the workflow was dispatched, both new steps were extracted from the YAML
(so that what ran was what the runner would run, dedented block scalar and
heredocs included) and executed against a real image:

- `docker build -t skills-gateway:ci .` from the repository's own Dockerfile
- the container started with the same flags the workflow uses — `--network host`,
  `--read-only`, tmpfs `/tmp` and `/data`, the fixture bind-mounted read-only
- the fixture step, then the product step: `EXIT=0`, with the same final line as
  the CI run above

The flow was established interactively first, which is where the two
non-obvious findings came from and why they are in the proposal rather than
discovered later in CI: a non-interactive mock token carries no `sub` and the
gateway rejects the id_token (`invalid_id_token ... {sub=null}`), and the SPA
CSRF posture requires the `XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN` on every
mutating call.

## Repository gates

Unchanged by this branch and re-run for completeness:

```
openspec validate --all --strict   → 32 passed, 0 failed
```

No Java or TypeScript source is touched, so `./mvnw clean verify`,
`pnpm test:stories`, `pnpm e2e`, `reqstool status local` and `mkdocs build
--strict` have nothing to say about this change that `main` did not already say.
No `@SVCs` annotation is added, moved or removed; the reqstool total stays at
218/218.
