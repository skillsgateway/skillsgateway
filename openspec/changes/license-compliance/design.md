# Design — license compliance for skills

## Context

The vetting chain (GW_0037–GW_0043) is the standard policy/violation path:
connectors run at ingestion against the quarantined SHA-pinned snapshot,
their findings are recorded per run, aggregation is fail-closed, the approval
gate refuses uncovered blocking findings, scoped/expiring waivers are the only
acceptance mechanism, continuous re-vetting re-runs the chain over approved
content, and everything lands on the append-only ledger. License compliance
must ride this path, not a parallel one.

The gateway's SBOM story today is its own CycloneDX SBOM (GW_0014,
`/actuator/sbom`) plus the per-snapshot content inventory
(`GET /api/snapshots/{id}/content`, GW_0020). License data belongs alongside
these as the per-snapshot supply-chain answer.

## Goals / Non-Goals

**Goals**

- Deterministic license detection at ingestion: same input → same findings,
  reviewable, waivable, re-vettable.
- Org-level allow/ban policy with sane defaults: upgrading blocks nothing.
- Unknown license is a first-class detectable state, not a silent gap.
- License data readable over the API for any snapshot.

**Non-Goals**

- Heuristic/statistical license similarity scoring (scancode-style). v1 is
  exact fingerprint matching; a snapshot that defeats it lands in the
  explicit `license-unknown` state, which the policy can be configured to
  block.
- Full SPDX expression algebra (`OR`/`AND`/`WITH` parsing with satisfiability
  against the policy). A manifest expression that is not a single known id is
  treated as unknown; organisations that need expression algebra can waive or
  extend later.
- Per-marketplace license policies. The issue asks for org-level lists; the
  retention-style per-marketplace override map is an obvious later extension
  and nothing in this design precludes it.
- Portal UI beyond what exists. License findings appear on the snapshot
  review surface through the existing vetting findings UI (GW_0042) —
  rule id, severity, location, message — which is exactly the evidence a
  reviewer needs. The proposal declares REST-only v1 for the report endpoint.
- Dependency-level license scanning inside skills (package manifests of
  vendored code). v1 scopes to the repository's own declared licenses.

## Decisions

### D1 — License policy is configuration, not API-managed runtime state

`skills-gateway.vetting.license.allowed` / `.banned` are ordinary
configuration properties, exactly like the retention policy, the re-vetting
mode, and the vetting timeout. They are **deliberately not** API-managed
estate objects, so the `skills-gateway.estate.*` obligation is satisfied in
its stronger form: the policy is declarative by construction — there is no
runtime state to reconcile, no drift to converge, and a GitOps deployment
carries the license policy in the same file as the rest of the vetting
configuration.

Rationale over an API + estate reconciliation design:

- Vetting policy must be attributable per chain run (GW_0049). Configuration
  changes arrive by deploy, and the connector stamps a digest of the policy
  into its recorded `version`, so every run names the policy it ran under.
  Mutable-at-runtime lists would change the meaning of "the chain as
  configured right now" between runs without any deploy boundary to anchor
  attribution to.
- The existing precedent: no vetting connector's rules are API-managed; the
  chain has no runtime mutation surface at all, on purpose (there is not even
  an off switch). An API-managed license policy would be the first, and would
  need its own roles, audit events, estate block and portal surface for no
  additional capability — a config change plus an on-demand re-vet
  (GW_0049) already turns a new policy into fresh evidence now.

### D2 — Detection is a deterministic three-source SPDX-id match

Sources, in tree order:

1. **License files**: any tree entry whose file name (case-insensitive,
   optional extension) is `LICENSE`, `LICENCE`, `COPYING`, `COPYING3`, or
   `UNLICENSE` — at any depth, so per-plugin license files are seen.
2. **SPDX tags**: within a license file, a `SPDX-License-Identifier: <id>`
   line wins outright (it is the file's own declaration).
3. **Manifest metadata**: the marketplace manifest's top-level
   `metadata.license` / `license` field and each plugin's `license` field,
   taken as an SPDX id verbatim (case-normalised against the known-id table).

License file text is identified by a fixed fingerprint table of distinctive
phrases for the common licenses (MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause,
ISC, MPL-2.0, EPL-2.0, LGPL-2.1, LGPL-3.0, GPL-2.0, GPL-3.0, AGPL-3.0,
Unlicense, CC0-1.0, CC-BY-4.0, CC-BY-SA-4.0). Matching is normalised
(lowercase, collapsed whitespace) substring/anchor matching — no scoring, no
thresholds. Anything else is `unknown`. The table lives in one class
(`LicenseDetector`) with a version string; changing the table bumps the
connector version.

Rejected alternative: bundling a scancode/askalono-style matcher. Heavy
dependency, non-deterministic across versions, and overkill for skill repos —
the fail-closed `unknown` state plus waivers covers the tail honestly.

### D3 — Findings and severities (the policy semantics)

One finding per detection source occurrence, standard `Finding` shape:

| rule id | when | severity |
|---|---|---|
| `license-detected` | a license was identified (always recorded) | INFO |
| `license-banned` | identified license is on the ban list | CRITICAL |
| `license-not-allowed` | allow list non-empty, identified license not on it | HIGH |
| `license-unknown` | license file/manifest value identifies nothing | HIGH if allow list non-empty, else LOW |
| `license-missing` | no license information anywhere in the snapshot | HIGH if allow list non-empty, else LOW |

`Verdict.of` then does what it always does: CRITICAL/HIGH → FAIL (blocks),
LOW/MEDIUM → WARN (clears with a visible warning), INFO → PASS. Defaults
(both lists empty) therefore warn on unknown/missing and pass on everything
identified — upgrade-safe. The ban list is checked before the allow list so a
license on both is reported as banned (the stronger statement). Each blocking
finding is waivable by its rule id through the existing waiver mechanism —
no new acceptance path.

### D4 — The report endpoint recomputes from the pinned tree

`GET /api/snapshots/{id}/licenses` answers from a fresh deterministic
detection over the snapshot's pinned commit (the `SnapshotContentService`
pattern), plus the policy evaluation under the configuration in force —
rather than parsing the latest chain run's stored findings. Detection is
cheap (skill repos are small, license files smaller), the answer can never be
absent for a pre-feature snapshot with no license-scan run, and it cannot
drift from what a re-vet would say. The gate still reads only recorded runs;
this endpoint is a read surface, not evidence. Authorization matches the
content inventory endpoint (`/snapshots/{id}/content`): any authenticated
session.

### D5 — Reuse, not parallel machinery

- Connector: `LicenseScanConnector implements VettingConnector`, order 300
  (after secret-scan 100 and prompt-injection), `version()` =
  `<table-version>+policy:<digest>` so policy changes are chain-identity
  visible (GW_0049).
- The approval gate, waivers, re-vetting (warn/enforce), webhooks, ledger:
  untouched — license findings are findings.
- Estate reconciler, roles, portal: untouched (D1, Non-Goals).

## Risks / Trade-offs

- [Fingerprint table misses a real license → `license-unknown`] → the state
  is explicit, warn-by-default, blockable-by-policy, waivable by rule id;
  the table is one class away from extension and version-bumped.
- [`license-detected` INFO findings add noise to clean verdicts] → INFO is
  already the established "recorded, not judged" channel (`file-not-scanned`
  uses it); it is what makes detection auditable from the ledger and the run.
- [Adding a third connector changes chain shape] → `VettingTests` asserts
  positions `containsExactly(0, 1)`; the assertion becomes derived from the
  configured chain (same property, no weakening). All other suites use clean
  fixtures which now gain a LOW `license-missing` warning — WARN clears, so
  no existing approval path changes.
- [Recompute-on-read endpoint can disagree with an old run's findings after
  a policy change] → intended: the endpoint states current policy truth,
  the run states historical evidence; both are labelled as such in docs.

## Migration Plan

Pure addition: new properties default to empty lists, new connector defaults
to warn-only behavior, new endpoint is read-only. No schema change beyond
none at all (findings persist through the existing vetting tables). Rollback
is reverting the deploy.

## Open Questions

None blocking. Per-marketplace policy overrides and SPDX expression algebra
are recorded as non-goals / later extensions.
