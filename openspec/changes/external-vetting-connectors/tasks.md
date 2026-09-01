# Tasks: external-vetting-connectors

Trust-boundary change: the `.claude/skills/old-coder` discipline applies. The
executable spec (`spec-approval.md`) is written first; every fail-closed behaviour
has an adversarial test; the run is closed by a gauntlet and `evidence.md`.

## 1. Spec (old-coder SPEC)

- [x] 1.1 Write the executable spec to `spec-approval.md` (named test list for
  GW_0142–GW_0145, negative constraints, setup plan, no new dependencies).
- [x] 1.2 Autonomous run: spec approval not obtained; recorded in `evidence.md`
  with correspondingly lower confidence.

## 2. Requirements (reqstool SSOT, before code)

- [x] 2.1 Add GW_0142–GW_0145 to `docs/reqstool/requirements.yml`.
- [x] 2.2 Add SVC_GW_0142–SVC_GW_0145 to `software_verification_cases.yml`.
- [x] 2.3 Confirm GW_0142–GW_0145 unused elsewhere (GW_0132–GW_0141 are claimed by
  other in-flight branches).

## 3. ADR

- [x] 3.1 Write ADR 0009 (`docs/decisions/0009-external-vetting-connector-contract.md`).
- [x] 3.2 Add it to `docs/manual/reference/decisions.md`.

## 4. Implementation

- [x] 4.1 `ExternalConnectorProperties` — bound config, validation, defaults, the
  reserved-name and url-scheme guards, write-only credential.
- [x] 4.2 `ExternalVetRequest` / `ExternalVetResponse` — the wire contract.
- [x] 4.3 `ExternalVettingConnector` — synchronous call, fail-closed mapping at
  every branch, worst-of, `pending` seam, bounded request/response.
- [x] 4.4 `ExternalVettingConnectorRegistrar` + `ExternalVettingConfiguration` —
  bind the list, register a bean per entry, uniqueness guard.
- [x] 4.5 `@Requirements` annotations on the implementing methods.

## 5. Tests (old-coder RED→GREEN + adversarial)

- [x] 5.1 `ExternalVettingConnectorUnitTests` (pure, container-free): happy paths,
  worst-of, `pending`, credential, binary-unscanned, and the full fail-closed
  matrix (GW_0142–GW_0145). 20 tests.
- [x] 5.2 `ExternalVettingConnectorTests` (integration, real `VettingService` over
  an ingested snapshot + in-process endpoint): pass clears, fail persists findings
  and report link, worst-of blocks, `pending` blocks and cannot be approved, the
  fail-closed matrix leaves the snapshot held. Carries SVC_GW_0142–0145.
- [x] 5.3 `ExternalConnectorRegistrationTests` (own context): a configured
  connector joins the chain in order and names itself in the chain identity.
- [x] 5.4 Manual mutation on the three load-bearing branches (non-2xx→pass,
  unknown-state→pass, drop worst-of); each killed; recorded in `evidence.md`.

## 6. Docs (same PR)

- [x] 6.1 `reference/configuration.md` — External connectors section + table.
- [x] 6.2 `concepts/vetting.md` — external connectors; corrected LLM-review note.

## 7. Gauntlet + evidence

- [x] 7.1 Java compile (main + test) clean.
- [x] 7.2 Unit suite green (20/20); 3/3 mutants killed.
- [ ] 7.3 Container-backed integration tests, `./mvnw clean verify`, reqstool,
  openspec validate, mkdocs — run serially pre-merge (the shared podman VM's
  Floci dev-service container will not start in this worktree; see `evidence.md`).
- [x] 7.4 `evidence.md` written with pasted result tails and the mutation log.

## 8. Archive

- [ ] 8.1 `/opsx:archive` as the final commit, after gates pass pre-merge.
