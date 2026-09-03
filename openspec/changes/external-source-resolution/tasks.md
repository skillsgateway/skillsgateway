# Tasks: external-source-resolution

## 1. Requirements (SSOT first)

- [ ] 1.1 Add GW_0155 (resolution into quarantine, pinned), GW_0156
      (deterministic composite snapshot with a gateway-local manifest), GW_0157
      (address, redirect and transport policy), GW_0158 (resource bounds) and
      GW_0159 (failed resolution rejects, nothing half-resolved) to
      `docs/reqstool/requirements.yml`
- [ ] 1.2 Add SVC_GW_0155 – SVC_GW_0159 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`
- [ ] 1.3 GW_0003, GW_0150, GW_0151 and GW_0152 are left untouched: the default
      configuration is unchanged and GW_0152 keeps its wording, which still
      describes the failure paths this change adds

## 2. Tests (red before green)

- [ ] 2.1 `SourceAddressPolicyTests` (`@SVCs({"SVC_GW_0157"})`): the two tiers —
      link-local including `169.254.169.254`, `fe80::1`, multicast, unspecified,
      `0.0.0.0/8` and IPv4-mapped forms of them refused under every
      configuration; loopback, RFC1918, CGNAT and unique-local refused unless
      private networks are permitted; a host resolving to a mixture of permitted
      and forbidden addresses refused as a whole
- [ ] 2.2 `SourceUrlPolicyTests` (`@SVCs({"SVC_GW_0157"})`): decimal, octal and
      hexadecimal IPv4 literals; embedded credentials; a port outside the policy;
      a redirect that changes host, downgrades the scheme, names a
      non-allowlisted scheme, or exceeds the hop bound
- [ ] 2.3 `PluginSourceTests` additions (`@SVCs({"SVC_GW_0150"})`): `..` and `.`
      segments in `owner/repo` yield no clone URL; a `github` source declaring
      `ref` or `sha` parses to the refusing form
- [ ] 2.4 `ManifestRewriterTests` (`@SVCs({"SVC_GW_0156"})`): the rewritten
      manifest, the upstream parent and its byte-exact original manifest,
      determinism and the three inputs that change the SHA, the reserved-directory
      collision, the four bad plugin names, the duplicate name, and the
      post-condition that the composite manifest passes `ManifestPolicy.validate`
- [ ] 2.5 `ResolutionBudgetTests` (`@SVCs({"SVC_GW_0158"})`): every bound refuses
      at the boundary and passes just under it; an expired deadline refuses
- [ ] 2.6 `ExternalSourceResolutionTests` (`@SVCs({"SVC_GW_0155", "SVC_GW_0156",
      "SVC_GW_0159"})`), its own Spring context with `enabled: true` and
      `github-base-url` pointed at the in-process fixture: held composite
      snapshot, content inventory, facade clone with no external URL, planted
      secret in the external repository blocking vetting, idempotent
      re-ingestion, a moved external head, unreachable source, mid-transfer
      failure, concurrent ingestion, off-host redirect, redirect to
      `169.254.169.254`, byte-budget breach, reserved-directory collision, and a
      declared `sha`
- [ ] 2.7 `GitHttpFixture`: an in-process smart-HTTP git server over
      `com.sun.net.httpserver.HttpServer` and JGit's `UploadPack`, with
      programmable redirect, truncation and byte-inflation behaviour, and a
      record of every path requested so a test can assert a target was never
      contacted. No container, no new dependency
- [ ] 2.8 Confirm `IngestionTests.externalPluginSourceIsRejectedAndCannotBeApproved`
      (SVC_GW_0003), the `HostedLifecycleTests` key-per-type case,
      `ManifestPolicyTests`, `ExternalSourceAdmissionTests` and
      `PluginSourceTests` still pass **unmodified** under the default
      configuration

## 3. Transport and policy

- [ ] 3.1 `SourceAddressPolicy`: the two-tier `InetAddress` classification;
      `@Requirements({"GW_0157"})`
- [ ] 3.2 `SourceUrlPolicy`: scheme, port, userinfo and redirect-transition rules
      over a URL, independent of any connection; `@Requirements({"GW_0157"})`
- [ ] 3.3 `GuardedHttpConnectionFactory`: resolve once, validate every address,
      connect to the validated address with the hostname preserved for TLS, no
      JDK redirect following, per-hop re-validation, timeouts, and the counting
      response stream that enforces `max-received-bytes`;
      `@Requirements({"GW_0157", "GW_0158"})`
- [ ] 3.4 Install it per fetch via `FetchCommand.setTransportConfigCallback` and
      `TransportHttp.setHttpConnectionFactory` — never
      `HttpTransport.setConnectionFactory`, which is a JVM-wide static

## 4. Resolution

- [ ] 4.1 `SkillsGatewayProperties.ExternalSources`: `githubBaseUrl`,
      `allowPrivateNetworks` and a nested `Budgets` record, all with defaults, so
      an absent block is the shipped default
- [ ] 4.2 `PluginSource.GitHub`: `ref`/`sha` fields parsed and refused by name;
      `cloneUrl()` derives from the configured base and refuses `.`/`..`
      segments; `@Requirements({"GW_0150", "GW_0155"})`
- [ ] 4.3 `ManifestPolicy.evaluate(byte[]) -> Evaluation` returning the
      violation, the parsed manifest and the admitted sources; `validate` keeps
      its signature and delegates; `@Requirements({"GW_0152"})`
- [ ] 4.4 `ExternalSourceResolver`: fetch each admitted source into the
      marketplace quarantine under a scaffolding ref, deduplicate identical
      sources, apply the per-source and per-closure budgets and the deadline,
      prune the scaffolding refs in a `finally`, and return either the resolved
      set or a violation; `@Requirements({"GW_0155", "GW_0158"})`
- [ ] 4.5 `ResolutionBudget`: the mutable accumulator for bytes, objects and time
      across one resolution, plus the post-fetch tree walk that enforces inflated
      bytes, ratio, object count, blob size and tree depth;
      `@Requirements({"GW_0158"})`

## 5. Rewrite

- [ ] 5.1 `ManifestRewriter`: in-core `DirCache` composition of the upstream tree
      plus each graft under `_plugins/<name>`, the rewritten manifest blob, the
      commit parented on the upstream commit with a fixed identity and the
      provenance message, and the graft-hazard refusals;
      `@Requirements({"GW_0156"})`
- [ ] 5.2 The GW_0152 post-condition: the rewritten manifest is run back through
      `ManifestPolicy.validate` and a non-null result refuses the composite;
      `@Requirements({"GW_0152", "GW_0156"})`

## 6. Ingestion wiring

- [ ] 6.1 `IngestionService.ingestLocked`: evaluate the manifest before pinning,
      resolve and rewrite when there are admitted sources, pin the served commit,
      and keep the existing dedupe, insert and vet steps unchanged;
      `@Requirements({"GW_0155", "GW_0156", "GW_0159"})`
- [ ] 6.2 The failure path: a resolver or rewriter violation records the snapshot
      at the upstream SHA in `rejected`, with no composite ref written

## 7. Documentation (same PR)

- [ ] 7.1 `docs/manual/reference/configuration.md`: the new
      `external-sources.*` keys, the budgets block, and the hardening note that
      egress isolation remains the primary control
- [ ] 7.2 `docs/manual/concepts/trust-boundaries.md`: the outbound path, the
      two-tier address policy, the redirect rules and what is deliberately not
      claimed
- [ ] 7.3 `docs/manual/concepts/snapshots-and-ledger.md`: the composite SHA, the
      upstream commit as its parent, and how to diff the transformation
- [ ] 7.4 `docs/manual/architecture.md`: the ingestion component and the Phase 2
      roadmap entry
- [ ] 7.5 `docs/manual/reference/compatibility.md` (the source-form table) and
      `docs/manual/guides/approving-snapshots.md` (what a reviewer sees for an
      external plugin, and what each new violation means)
- [ ] 7.6 `docs/decisions/0011-external-plugin-sources.md`: the increment table's
      second row moves to shipped, with the two deviations recorded (the policy
      version, and the hardened transport not covering the upstream fetch)

## 8. Gates and evidence (old-coder gauntlet)

- [ ] 8.1 `./mvnw clean verify`
- [ ] 8.2 `(cd src/main/frontend && pnpm test:stories)` and `pnpm e2e`
- [ ] 8.3 `reqstool status local -p docs/reqstool` ends `PASS`
- [ ] 8.4 `openspec validate --all --strict`
- [ ] 8.5 `mkdocs build --strict`
- [ ] 8.6 `evidence.md`: the spec ↔ test mapping, the adversarial cases with
      their outcomes, the manual mutants, the negative controls, and the commit
      SHA of one final fresh run
