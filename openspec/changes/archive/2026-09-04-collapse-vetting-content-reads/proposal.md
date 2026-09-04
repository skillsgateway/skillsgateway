# Proposal: collapse-vetting-content-reads

## Why

`QuarantineSnapshot.walk` is the only way a connector sees a snapshot, and it
materializes **every blob** before the visitor is given a chance to look at the
path. Each connector calls it independently, so one chain run over the built-in
chain walks and inflates the same tree three times:

- `secret-scan` reads every file — it genuinely needs all of them.
- `prompt-injection` reads `.md`, `.mdc`, `.markdown` and `.txt`, which in a
  skills marketplace is most of the tree, and discards the rest *after* it has
  been inflated.
- `license-scan` reads a handful of `LICENSE`-shaped files and one manifest, and
  inflates the entire tree to find them.

Adding an external connector — `GW_0144 — Operator-configured external vetting
connectors join the ordered chain` — adds a fourth full walk.

This is not a one-off ingestion cost. `GW_0049 — Continuous re-vetting of
approved snapshots` re-runs the chain over already-approved content on a
schedule, so the multiplier applies to the whole approved estate on every sweep.

Issue [#252](https://github.com/skillsgateway/skillsgateway/issues/252).

## What Changes

- **A connector declares which paths it reads** — `GW_0162 — Bounded
  single-pass snapshot content access for a vetting chain run`.
  `SnapshotUnderVetting.walk(Predicate<String>, FileVisitor)` becomes the
  primitive; the existing single-argument `walk` is a default that selects
  everything. `QuarantineSnapshot` consults the predicate *before* opening a
  blob, so a file no connector asked for is never opened at all.
- **The tree is walked once per run.** `QuarantineSnapshot` indexes the pinned
  commit's tree — path and blob id, no content — in its constructor, and every
  connector's `walk` iterates that index instead of opening a new `TreeWalk`.
- **Content read by one connector is reused by the next**, from a per-run cache
  keyed by blob id, bounded by a new
  `skills-gateway.vetting.content-cache-bytes` (default 32 MiB). Past the bound
  content is re-read rather than retained: a large snapshot degrades in speed,
  never in coverage. The bound is not a nicety — quarantined content is
  attacker-supplied, and an unbounded per-run cache would turn any large
  upstream repository into a memory-exhaustion primitive against the gateway.
- Requirement GW_0162 with SVC_GW_0162.

**No connector's verdict changes.** Every connector already filtered by path
inside its visitor; the predicate moves that same test one step earlier. A file
over `max-file-bytes` is still visited with `null` content, so the
`file-not-scanned` findings that make a coverage gap visible — `GW_0143 — A
clean vetting pass records what it examined` — are unaffected, and `license-scan` still records an oversized `LICENSE` as an
explicit unknown.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `snapshot-vetting`: the snapshot view handed to connectors under
  `GW_0037 — Ordered vetting connector chain at ingestion` gains a
  path selection applied before content is read, a single tree walk per chain
  run, and a bounded per-run reuse of content across the connectors of that run.

## Impact

- **DB**: none.
- **Backend**: `SnapshotUnderVetting` (new primitive `walk` overload),
  `QuarantineSnapshot` (tree index + bounded content cache),
  `SecretScanConnector`, `PromptInjectionConnector`, `LicenseDetector`,
  `ExternalVettingConnector` (each declares its selection),
  `SkillsGatewayProperties.Vetting` (+`contentCacheBytes`), `VettingService`
  (passes the bound).
- **API**: none. `SnapshotUnderVetting` is an internal SPI; no wire format,
  no endpoint and no external connector contract changes.
- **Trust boundary**: none crossed. Connectors still see only paths and bytes of
  one pinned commit, and the new bound closes a memory-exhaustion path rather
  than opening one.
- **Docs** (same PR): `reference/configuration.md`.
