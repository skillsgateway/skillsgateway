# Proposal: skill-conformance-connector

## Why

The built-in chain — `secret-scan`, `prompt-injection`, `license-scan` — asks one
question three ways: *is this content dangerous?* Nothing in it asks whether a
skill is **well formed**. A `SKILL.md` with no frontmatter, a `name` that does
not match its directory, or a `description` the agent will never see is not a
security problem; it is a skill that will not load, or one that loads and is
never selected. Today the gateway serves it without comment, and the first
person to find out is whoever installed the marketplace.

That gap also costs the reviewer. When the only connectors that speak are
heuristic scanners, every objection a reviewer reads is a probability. A
conformance defect is not: "`plugins/hello/skills/hello/SKILL.md`: required
frontmatter field `description` is missing" is a fact, actionable by the
publisher without a judgement call. Prompted by the AWS Agent Registry
comparison in `docs/manual/concepts/related-work.md`, where every record is
validated against a pinned, dated protocol schema and the schema version is
recorded alongside the result.

Issue [#237](https://github.com/skillsgateway/skillsgateway/issues/237).

## What Changes

- **A fourth built-in connector, `skill-conformance` (order 400)** — `GW_INGEST_0028 —
  SKILL.md conformance against a pinned Agent Skills specification`. It reads
  every `<source>/skills/*/SKILL.md` the snapshot's manifest leads to and
  validates its YAML frontmatter against a **vendored, dated** copy of the
  agentskills.io Agent Skills specification. Each defect is an ordinary
  `Finding` on its own stable rule id, located at the file it was found in.
- **The specification is vendored, never fetched.**
  `vetting/agentskills-2026-08-04.json` ships as a classpath resource carrying
  its source URL, the upstream commit it was transcribed from, and the date it
  was pinned. A chain run that reached the network would not be reproducible,
  and re-vetting an approved snapshot has to answer "did the content change, or
  did the rules?" — which it cannot do if the rules can change under it
  silently.
- **The recorded connector version names the specification version.**
  `version()` answers `agentskills-2026-08-04+schema-<digest>+<posture>`, so a
  snapshot that cleared under one specification and is flagged under the next is
  attributable to the specification bump (`GW_VETTING_0012 — Continuous re-vetting of
  approved snapshots`).
- **Advisory by default, blocking on request.** With
  `skills-gateway.vetting.conformance.enforce` at its default `false`, every
  conformance defect is a `MEDIUM` finding — a `WARN` verdict, visible to the
  reviewer, blocking nothing. Set it to `true` and the same defects become
  `HIGH`, which blocks and is waivable like any other finding. A frontmatter
  field the pinned specification does not define is `INFO` under both postures.
- **One frontmatter parser, not two.** `policy/SkillFrontmatter` gains a
  non-throwing `parse` that returns the frontmatter mapping or the reason it
  could not be read; `tools(...)` is reimplemented on top of it with its
  fail-closed contract and its exception type unchanged.

## Capabilities

### New Capabilities

_None._ The chain gains a connector; the capability it belongs to already
exists.

### Modified Capabilities

- `snapshot-vetting`: the built-in chain established by `GW_VETTING_0001 — Ordered
  vetting connector chain at ingestion` gains a conformance connector that
  validates each skill's `SKILL.md` frontmatter against a pinned, dated
  specification and records that specification's version as its connector
  version.

## Impact

- **DB**: none. Verdicts and findings use the existing tables.
- **Backend**: new `SkillConformanceConnector` and `SkillSpec` (which loads the
  vendored resource), vendored `vetting/agentskills-2026-08-04.json`;
  `policy/SkillFrontmatter` (+`parse`, `tools` unchanged in behaviour);
  `SkillsGatewayProperties.Vetting` (+`conformance.enforce`);
  `ExternalConnectorProperties.RESERVED_NAMES` (+`skill-conformance`).
- **API**: none. The connector appears in the existing chain listing and verdict
  payloads; no schema, path or field changes, so `openapi.json` is unchanged.
- **Trust boundary**: none crossed. The connector reads paths and bytes of one
  pinned commit through the same `SnapshotUnderVetting` seam every built-in
  uses, and can only ever add findings. The one adversarial surface it does own
  is a YAML parser pointed at attacker-supplied frontmatter, which is why the
  loader's alias, nesting and code-point limits are set explicitly and tested
  rather than inherited.
- **Docs** (same PR): `concepts/vetting.md`, `reference/configuration.md`,
  `reference/api/marketplaces.md`.
