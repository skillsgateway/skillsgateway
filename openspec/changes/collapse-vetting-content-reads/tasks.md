# Tasks: collapse-vetting-content-reads

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0162 (one tree walk per chain run; content read only for the
      paths a connector's selection asks for; content reused across the
      connectors of a run within a configured bound, re-read past it; identical
      content presented to every connector, oversize files still visited as
      unread) to `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0162 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Backend (SVC_GW_0162)

- [x] 2.1 `SnapshotUnderVetting`: `walk(Predicate<String>, FileVisitor)` becomes
      the primitive; `walk(FileVisitor)` a default selecting everything
- [x] 2.2 `QuarantineSnapshot`: eager path/blob-id tree index in the
      constructor; `walk` iterates it, consults the predicate before opening a
      blob, and serves content from a per-run `ObjectId`-keyed cache bounded by
      `contentCacheBytes`. Annotated `@Requirements({"GW_0162"})`
- [x] 2.3 `SkillsGatewayProperties.Vetting`: `contentCacheBytes` with a 32 MiB
      default and a non-positive fallback, documented on the record
- [x] 2.4 `VettingService.open`: pass the bound through
- [x] 2.5 Declare each connector's selection — `SecretScanConnector` (all),
      `PromptInjectionConnector` (instruction suffixes), `LicenseDetector`
      (licence-shaped files and the manifest), `ExternalVettingConnector` (all)

## 3. Tests (never weakening an existing SVC test)

- [x] 3.1 `QuarantineSnapshotTests` in `dev.skillsgateway.server.vetting` over a
      real JGit repository, `@SVCs({"SVC_GW_0162"})`: a blob outside the
      selection is never opened (proved by deleting its object from the object
      database — an implementation that materializes it throws); a blob two
      walks select is inflated once (the second walk yields the identical
      array); a blob over `max-file-bytes` is still visited with `null` content;
      content past the cache bound is still served correctly
- [x] 3.2 Update the `SnapshotUnderVetting` fakes in
      `ExternalVettingConnectorUnitTests` to the new primitive
- [x] 3.3 Confirm the existing connector SVC tests still pass unchanged — they
      are the evidence that no verdict moved

## 4. Docs (same PR)

- [x] 4.1 `docs/manual/reference/configuration.md`: the new property in the
      vetting YAML sample and the property table

## 5. Gates and archive

- [ ] 5.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [ ] 5.2 `openspec/changes/collapse-vetting-content-reads/evidence.md`
- [ ] 5.3 Archive the change as the final commit of the PR
