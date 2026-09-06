# Tasks: skill-conformance-connector

## 1. Requirements (SSOT first)

- [x] 1.1 Add GW_0167 (validate each skill's SKILL.md frontmatter against a
      specification pinned in the repository; record that specification's
      version as the connector version; advisory by default and blocking only
      when configured; never fetch the specification at vet time) to
      `docs/reqstool/requirements.yml`
- [x] 1.2 Add SVC_GW_0167 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. The pinned specification

- [x] 2.1 Vendor `src/main/resources/vetting/agentskills-2026-08-04.json`: the
      frontmatter constraint table plus a `source` block naming the URL, the
      upstream commit transcribed from, the reference validator cross-checked
      against, and the date pinned
- [x] 2.2 `src/main/resources/vetting/README.md`: provenance and how to bump the
      pin
- [x] 2.3 `SkillSpec`: loads the resource, exposes the constraint table and a
      version string carrying the dated pin and a digest of the table;
      registers the resource for native image

## 3. Backend (SVC_GW_0167)

- [x] 3.1 `policy/SkillFrontmatter`: non-throwing `parse` returning
      fields / absent / malformed; `tools(...)` reimplemented on it with its
      contract and exception type unchanged; `LoaderOptions` limits set
      explicitly (aliases, nesting depth, code points)
- [x] 3.2 `SkillConformanceConnector` implementing `VettingConnector` —
      `name()` `skill-conformance`, `order()` 400, `version()` from `SkillSpec`
      plus the posture, `description()`, `vet(...)` producing per-file findings.
      Annotated `@Requirements({"GW_0167"})`
- [x] 3.3 `SkillsGatewayProperties.Vetting`: `conformance.enforce`, default
      `false`, documented on the record
- [x] 3.4 `ExternalConnectorProperties.RESERVED_NAMES`: add `skill-conformance`

## 4. Tests (never weakening an existing SVC test)

- [x] 4.1 `SkillConformanceConnectorTests` (unit, `@SVCs({"SVC_GW_0167"})`): a
      conformant skill passes; each required field missing or malformed yields
      its own finding; unknown fields are informational; a snapshot with no
      skills passes with a summary that says so; malformed YAML is a finding and
      never an exception
- [x] 4.2 Adversarial YAML in the same class: alias bomb, deep nesting, oversize
      frontmatter — each refused as a finding, proved to be refused by the
      loader limits rather than assumed
- [x] 4.3 `SkillConformanceTests` (integration, `@SVCs({"SVC_GW_0167"})`): the
      connector runs in the real chain, the verdict warns and does not block by
      default, and the recorded chain identity names the pinned specification
- [x] 4.4 `SkillConformanceEnforceTests` (integration, own context with
      `enforce=true`, `@SVCs({"SVC_GW_0167"})`): the same defect blocks
      approval and is cleared by an ordinary scoped waiver
- [x] 4.5 Make the shared `AbstractGatewayTest` and e2e upstream fixtures
      conformant, so "clean fixture" keeps meaning a clean chain
- [x] 4.6 Confirm `SkillFrontmatterTests` and the policy SVC tests pass
      unchanged — they are the evidence the policy gate did not move

## 5. Docs (same PR)

- [x] 5.1 `docs/manual/concepts/vetting.md`: the connector, its pinned
      specification version, and what it blocks versus advises
- [x] 5.2 `docs/manual/reference/configuration.md`: the new property, the YAML
      sample, and the posture warning
- [x] 5.3 `docs/manual/reference/api/marketplaces.md`: the built-in list on the
      connector enable/disable endpoint

## 6. Gates and archive

- [x] 6.1 `./mvnw clean verify`, `pnpm test:stories`, `pnpm e2e`,
      `reqstool status local -p docs/reqstool`, `openspec validate --all
      --strict`, `mkdocs build --strict`
- [ ] 6.2 `openspec/changes/skill-conformance-connector/evidence.md`
- [ ] 6.3 Archive the change as the final commit of the PR
