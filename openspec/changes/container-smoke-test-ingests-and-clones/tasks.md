# Tasks: container-smoke-test-ingests-and-clones

## 1. Requirements (SSOT first)

- [x] 1.1 No new requirement and no new SVC — see the proposal. The change adds
      a CI gate over the shipped artifact, not a new verified behaviour.

## 2. Prove the flow before writing the workflow

- [x] 2.1 Establish that a real OIDC login is reachable from plain `curl`
      against a mock provider with `interactiveLogin` false
- [x] 2.2 Establish that the non-interactive token needs an explicit `sub`, and
      what the gateway does without one
- [x] 2.3 Drive register → ingest → approve → mint PAT → clone end to end

## 3. The workflow

- [x] 3.1 Add the mock provider service to the image job
- [x] 3.2 Build the marketplace fixture in the job with plain git, conformant
      with the skill specification so the vetting chain does not hold it
- [x] 3.3 Bind-mount the fixture into the container and add the OIDC, claim
      mapping and URL-scheme wiring the flow needs
- [x] 3.4 Add the step: register, ingest (assert `held`), approve, clone through
      the facade with a PAT, assert the skill file and its content

## 4. Verify

- [x] 4.1 Run the fixture step and the smoke step locally against the real
      container image built from the Dockerfile
- [x] 4.2 **Prove it fails when the product is broken** — with the fixture
      unreachable, health still reports `UP` (the old smoke test passes) while
      the new step exits non-zero naming the ingest failure
- [ ] 4.3 Run the workflow on the branch via `workflow_dispatch` and confirm green
- [ ] 4.4 `evidence.md` with both runs and the gate tails
