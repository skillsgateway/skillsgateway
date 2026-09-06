# Tasks: read-only-forge-mirror

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0169 (read-only forge mirror of approved content), GW_0170 (the
      mirror is never an enforcement path), GW_0171 (revocation propagates to the
      mirror) and GW_0172 (mirror drift is reportable) to
      `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0169, SVC_GW_0170, SVC_GW_0171 and SVC_GW_0172
      (GIVEN/WHEN/THEN) to `docs/reqstool/software_verification_cases.yml`

## 2. Configuration and policy

- [x] 2.1 `SkillsGatewayProperties.Mirror` nested record, `enabled` defaulting to
      false, with `toString` overridden so the credential cannot be logged
      (GW_0169)
- [x] 2.2 `MirrorUrlPolicy`: the same scheme allowlist registration applies, plus
      a refusal of a URL that embeds a credential (GW_0169)
- [x] 2.3 `MirrorTarget.from`, validating at startup and refusing to start on an
      enabled-but-unusable configuration; nothing is contacted (GW_0169)

## 3. The mirror itself

- [x] 3.1 `GitStorage.isServedRef` plus the two served-namespace constants;
      `GitFacadeConfiguration` uses them instead of its private copies
- [x] 3.2 `ServedContentChangedEvent`, raised by `ApprovalService` after
      publication and by `RevetService` after a revocation — including one whose
      unpublish failed (GW_0169, GW_0171)
- [x] 3.3 `MirrorPublicationListener` and `MirrorConfiguration`'s single daemon
      thread: the wiring that keeps the mirror off the approving thread (GW_0170)
- [x] 3.4 `ForgeMirrorService.reconcileLater` / `reconcile`: push the served
      reference set, delete what the mirror still holds inside those namespaces,
      retry, then record the failure as drift (GW_0169, GW_0170, GW_0171)
- [x] 3.5 `mirror-updated` and `mirror-push-failed` ledger events under a
      declared system actor in `AdminAuditLogger` (GW_0171)
- [x] 3.6 `@Requirements` annotations on the implementing methods

## 4. The drift report

- [x] 4.1 `MirrorReport` with `@Schema` annotations, fail-closed on an
      unreachable mirror (GW_0172)
- [x] 4.2 `ForgeMirrorService.report`: compare against published storage, not
      against push history (GW_0172)
- [x] 4.3 `MirrorController` — `GET /api/mirror/drift`, `roleService.requireAdmin`
      (GW_0172)
- [x] 4.4 Classify the route on `MachineApiRegistry`'s unreachable list, and add
      it to `RoleEnforcementTests`' privileged-read walk
- [x] 4.5 Regenerate `src/main/frontend/openapi.json` and `src/api/types.gen.ts`

## 5. Tests

- [x] 5.1 `MirrorUrlPolicyTests` (`@SVCs({"SVC_GW_0169"})`): the scheme allowlist,
      an address with no usable scheme, a credential embedded in the URL, and an
      `@` in a path that is not one
- [x] 5.2 `ForgeMirrorTests` (`@SVCs({"SVC_GW_0169", "SVC_GW_0171",
      "SVC_GW_0172"})`): against a real bare repository over `file://` —
      quarantine is not mirrored, approval mirrors exactly the served set, a
      second held snapshot never appears, seeded drift is named ref by ref,
      revocation removes the snapshot and repairs the drift, and the report is
      admin-only and carries no credential
- [x] 5.3 `ForgeMirrorFailureTests` (`@SVCs({"SVC_GW_0170", "SVC_GW_0171",
      "SVC_GW_0172"})`): the mirror pointed at a path that is not a repository —
      the approval returns, a real `git clone` gets the approved commit, the
      revocation takes effect in full, the clone then fails, and the report says
      unreachable rather than in sync
- [x] 5.4 `ForgeMirrorDisabledTests` (`@SVCs({"SVC_GW_0169"})`): the shared
      context, carrying no mirror settings, pushes nothing and writes no mirror
      ledger entry

## 6. Documentation (same PR)

- [x] 6.1 `docs/manual/guides/read-only-forge-mirror.md` — enabling it, what it
      is not, and reading the drift report; added to `mkdocs.yml` nav
- [x] 6.2 `docs/manual/reference/configuration.md` — the `skills-gateway.mirror`
      keys
- [x] 6.3 `docs/manual/reference/api/mirror.md` — the endpoint and its fields;
      added to `mkdocs.yml` nav
- [x] 6.4 `docs/manual/architecture.md` — the mirror as a component, and its
      place in the roadmap
- [x] 6.5 `docs/manual/concepts/trust-boundaries.md` — the mirror is outside the
      trust boundary and is not an enforcement path

## 7. Gates and evidence

- [x] 7.1 One fresh run of every gate after the last code edit, pasted into
      `openspec/changes/read-only-forge-mirror/evidence.md` with the commit SHA
- [x] 7.2 Archive the change as the final commit of the PR
