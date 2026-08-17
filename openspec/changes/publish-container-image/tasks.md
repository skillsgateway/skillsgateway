# Tasks: publish-container-image

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0072 (container image publication) to
      `docs/reqstool/requirements.yml` — GHCR publication by immutable digest,
      main + release tags, digest surfaced, SBOM attestation, publish only from
      push events after the smoke test.
- [ ] 1.2 Add SVC_GW_0072 to `docs/reqstool/software_verification_cases.yml`
      (verification: automated-test) — the workflow file carries the publish
      contract (image name, both tag channels, push-event gate, permissions,
      attestation step ordered after push).

## 2. Workflow implementation

- [ ] 2.1 Extend `.github/workflows/native.yml` trigger with `tags: ['v*']` and
      add job permissions `packages: write`, `id-token: write`,
      `attestations: write` (keep `contents: read`).
- [ ] 2.2 Add publish steps gated on `github.event_name == 'push'`: GHCR login
      with `GITHUB_TOKEN`, tag `skills-gateway:ci` as
      `ghcr.io/skillsgateway/skillsgateway:{sha-<commit>,latest}` on main and
      `:<tag>` on `v*`, push, capture the digest as a step output.
- [ ] 2.3 Write the pushed digest and tags to `$GITHUB_STEP_SUMMARY`.
- [ ] 2.4 Attest the SBOM (`actions/attest-sbom`) against the pushed digest,
      predicate `target/classes/META-INF/sbom/application.cdx.json`, ordered
      after the push.

## 3. Verification

- [ ] 3.1 Extend `PackagingTests` with SVC_GW_0072 assertions on native.yml:
      image name, `sha-`/`latest`/`v*` tag channels, publish steps gated on
      push events (never schedule/dispatch), required permissions present,
      attestation step after push. Add `@SVCs("SVC_GW_0072")` /
      `@Requirements` annotations. Prove the test fails against the
      pre-change workflow, then passes.

## 4. Documentation (same PR)

- [ ] 4.1 Update `docs/manual/` (installation/reference): image coordinates
      `ghcr.io/skillsgateway/skillsgateway`, digest pinning as the supported
      consumption mode, private-package pulls need `read:packages`, note that
      visibility can be flipped to public independently of the repo.

## 5. Gates & evidence

- [ ] 5.1 Run all five gates fresh after the last edit; write
      `openspec/changes/publish-container-image/evidence.md` with command tails
      and commit SHA.
- [ ] 5.2 Sync the delta into `openspec/specs/release-packaging/spec.md` and
      archive the change as the final commit.
