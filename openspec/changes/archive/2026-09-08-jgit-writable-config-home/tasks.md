# Tasks: jgit-writable-config-home

## 1. Investigate the exact JGit call path (design.md)

- [x] 1.1 Read `SystemReader.getXdgConfigDirectory`, `openJGitConfig`,
      `FS.FileStoreAttributes.getFileStoreAttributes`/`saveToConfig` from
      `org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r` sources (the
      version pinned in `pom.xml`) — confirms the two-step fallback
      (`XDG_CONFIG_HOME` → `$HOME/.config` → give up) and that the failing
      call path is the filesystem-timestamp-resolution cache, not `gc.auto`
      (issue #293 — confirmed unrelated, not implemented here)
- [x] 1.2 Reproduce the exact log lines with a standalone probe against the
      pinned JGit jar: `FS.getFileStoreAttributes(Path)` with `-Duser.home`
      pointed at a directory that exists but is not writable (`chmod 555`),
      `XDG_CONFIG_HOME` unset
- [x] 1.3 Re-run the same probe with `XDG_CONFIG_HOME` set to a writable
      directory: confirm no error is logged and that
      `$XDG_CONFIG_HOME/jgit/config` is actually written

## 2. Requirements (SSOT first)

- [x] 2.1 Add GW_0179 (the image and the chart point JGit's own cache at the
      writable temporary directory) to `docs/reqstool/requirements.yml`
- [x] 2.2 Add SVC_GW_0179 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 3. Packaging (SVC_GW_0179)

- [x] 3.1 `Dockerfile`: `ENV XDG_CONFIG_HOME=/tmp/xdg-config`, with a comment
      explaining why
- [x] 3.2 `helm/skills-gateway/templates/deployment.yaml`: the same variable,
      stated unconditionally in the pod spec, with a comment explaining why
      it is restated rather than left to the image default
- [x] 3.3 `helm lint helm/skills-gateway` and a full `helm template` render,
      confirming the rendered env entry

## 4. Tests (never weakening an existing SVC test)

- [x] 4.1 `PackagingTests`: new test method, `@SVCs({"SVC_GW_0179"})`,
      asserting the `Dockerfile` and the rendered `Deployment` both carry
      `XDG_CONFIG_HOME` under `/tmp`

## 5. Documentation (same PR)

- [x] 5.1 `guides/deploying-on-kubernetes.md`: "Identity and security
      context" gains a sentence on JGit's own scratch use of `/tmp`
- [x] 5.2 `guides/deploying-without-kubernetes.md`: "A writable `/tmp`, if you
      seal the root filesystem" gains the same explanation for plain
      Docker/other orchestrators
- [x] 5.3 `reference/configuration.md`: "Server and storage notes" gains a
      bullet alongside the existing `SKILLSGATEWAY_DATADIR` one

## 6. Gates and archive

- [x] 6.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [x] 6.2 `openspec/changes/jgit-writable-config-home/evidence.md`
- [x] 6.3 Archive the change as the PR's final commit
