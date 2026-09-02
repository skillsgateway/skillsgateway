# Tasks: external-plugin-sources

## 1. Requirements (SSOT first)

- [x] 1.1 Revise GW_0003 in `docs/reqstool/requirements.yml` (revision 0.1.0 →
      0.2.0): local-only rejection becomes the default-configuration behaviour,
      with the configured admission named as the exception. SVC_GW_0003 is left
      untouched and now also pins that default
- [x] 1.2 Add GW_0150 (typed plugin source model, fail-closed by type), GW_0151
      (configuration-gated admission with type/scheme/host/count bounds,
      disabled by default) and GW_0152 (no snapshot is held while a plugin
      source is not gateway-local) to `docs/reqstool/requirements.yml`
- [x] 1.3 Add SVC_GW_0150, SVC_GW_0151 and SVC_GW_0152 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Tests

<!-- Honest deviation from the old-coder loop: 2.1–2.3 were written after the
     code they cover, not proved red first. 2.4 did run red first, and caught a
     real defect — see evidence.md. -->


- [x] 2.1 `PluginSourceTests` (pure function, `@SVCs({"SVC_GW_0150"})`): every
      source form classifies to its variant — relative path, `github`, `git`,
      `git-subdir`, `npm`, `archive`; an unrecognised type, the key-per-type
      object shape, a non-string non-object value, and a local path escaping the
      repository each refuse with a violation naming the plugin and the form
- [x] 2.2 `ExternalSourceAdmissionTests` (pure function,
      `@SVCs({"SVC_GW_0151"})`): default configuration refuses; enabled admits;
      type outside `allowed-types` refuses; host outside a non-empty
      `allowed-hosts` refuses (including the `evil-github.com` near-miss against
      an allowlist of `github.com`); a derived URL whose scheme is outside
      `allowed-url-schemes` refuses; the `(max-sources + 1)`-th external source
      refuses; `npm` and `archive` refuse under *every* configuration including
      one that lists them in `allowed-types`
- [x] 2.3 `ManifestPolicyTests` (pure function, `@SVCs({"SVC_GW_0152"})`):
      the gate returns null — the value `IngestionService` maps to held — only
      when every source resolves inside the served snapshot; an admitted source
      is still a violation and reads differently from a refusal; the
      `max-sources` bound is reachable through a whole manifest.
      **Replaces the planned `ExternalSourceIngestionTests`**: with no resolver
      there is no external content for a container-backed test to exercise, and
      the invariant is a property of the function, not of one wiring. The
      integration path is covered by the unmodified `IngestionTests` and
      `HostedLifecycleTests` in 2.4
- [x] 2.4 Confirm the existing `IngestionTests` external-source case
      (`{"source": "github", "repo": "stranger/evil"}`) and the
      `HostedLifecycleTests` case (`{"github": "acme/elsewhere"}`) still pass
      **unmodified** under the default configuration — SVC_GW_0003 regression,
      never weakened

## 3. Typed source model

- [x] 3.1 `PluginSource` sealed interface in
      `dev.skillsgateway.server.ingestion` with `Local`, `GitHub`, `GitUrl`,
      `GitSubdir`, `Npm`, `Archive`, and a total `parse(JsonNode)` that returns a
      variant or a named refusal; `@Requirements({"GW_0150"})`
- [x] 3.2 `GitHub.cloneUrl()` — the single place the `owner/repo` →
      `https://github.com/owner/repo` convention lives

## 4. Admission

- [x] 4.1 `SkillsGatewayProperties`: `Ingestion` and `ExternalSources` nested
      records with defaults (`enabled=false`, `allowed-types=[github]`,
      `allowed-hosts=[]`, `max-sources=20`), following the `Sync`/`Vetting`
      pattern; a `null` block binds to the shipped default
- [x] 4.2 `ExternalSourceAdmission` record + `decide(PluginSource)` returning
      `LOCAL` / `ADMITTED` / `REFUSED(violation)`; `npm` and `archive` refused
      above the `enabled` branch so no configuration can admit them;
      `@Requirements({"GW_0151"})`
- [x] 4.3 `ManifestPolicy` becomes a `@Component` holding the admission,
      `validate` becomes an instance method, `validateSource` is replaced by the
      parse-then-decide path; `@Requirements({"GW_0003", "GW_0150", "GW_0151"})`
- [x] 4.4 `IngestionService`: take `ManifestPolicy` by constructor,
      `validateManifest` stops being `static`; an admitted-but-unresolved source
      yields the distinct violation so `ingestLocked`'s existing
      `violation == null ? HELD : REJECTED` mapping records it rejected;
      `@Requirements({"GW_0152"})` — the invariant is enforced in
      `ManifestPolicy.validate`, which is the one place a resolver will replace,
      and `IngestionService` keeps its unchanged violation-to-state mapping

## 5. Documentation (same PR)

- [x] 5.1 ADR 0011 (`docs/decisions/0011-external-plugin-sources.md`) + the
      `reference/decisions.md` index entry
- [x] 5.2 `docs/manual/architecture.md`: the "MVP scope: local sources only"
      paragraph (§4 Ingestion) and the Phase 2 roadmap entry — both record the
      staged reversal and cite ADR 0011; neither claims behaviour that has not
      shipped
- [x] 5.3 `docs/manual/reference/configuration.md`: the
      `skills-gateway.ingestion.external-sources.*` keys, and the note that this
      increment admits without resolving
- [x] 5.4 `docs/manual/concepts/trust-boundaries.md`: the held-only-if-local
      invariant, and that egress isolation — not URL validation — is the primary
      SSRF control when resolution arrives
- [x] 5.5 `docs/manual/reference/compatibility.md` (the source-form table) and
      `docs/manual/guides/approving-snapshots.md` (what each of the two
      violations means to a reviewer). `registering-a-marketplace.md` needed no
      change — it never states the source rules, it links to the compatibility
      matrix that does

## 6. Gates and evidence (old-coder gauntlet)

- [x] 6.1 `openspec validate --all --strict`
- [ ] 6.2 `./mvnw clean verify`, `reqstool status local -p docs/reqstool`,
      `mkdocs build --strict`
- [ ] 6.3 `evidence.md`: spec ↔ test mapping, the negative cases, and an honest
      record of what ran locally versus what is deferred to CI
