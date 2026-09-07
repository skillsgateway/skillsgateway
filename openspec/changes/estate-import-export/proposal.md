# Proposal: estate-import-export

> **Status: not implemented.** This change is the design artefact for
> [#152](https://github.com/skillsgateway/skillsgateway/issues/152) and ships
> alongside a **proposed** ADR. Nothing in `tasks.md` has been done, and the
> requirement ids below are reserved rather than allocated. It needs the owner's
> decision on
> [ADR 0014](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0014-estate-import-export.md)
> before implementation begins.

## Why

The gateway is the only door to a set of approved skills, and today it is also
the only reader of the estate behind that door. `StorageMigration` moves
repositories between two backends *of the same gateway*; there is no way to get
an estate **out** — to another instance, to a bare git host, or to an archive
that outlives the product.

The obstacle is not the git objects. It is that **the repositories are not the
estate**: what makes a SHA approved lives in eighteen PostgreSQL tables, and the
only current exit is to read whichever storage backend is configured in its own
terms and then reconstruct that state by hand. On the `object-store` backend
even the first half is impossible with ordinary tools, because the bucket holds
JGit DFS packs, not a repository any git client can clone.

Issue #152 is a set of questions rather than a design, and its triage says so:
worth a design pass, first increment export only, import deferred. ADR 0014
answers those questions. This change is the increment the ADR recommends.

## What Changes

- **A portable export, at two scopes (GW_0206 — Estate and marketplace export as
  a portable artefact).** One artefact format with two selections: a single
  marketplace, which is the useful unit for handing content to somebody else, or
  the whole estate, which is the useful unit for an archive. The manifest states
  its own scope and enumerates every category it deliberately left out, so a
  reader never has to infer whether something is absent by design or lost.
- **Readable without the product (GW_0207 — An export is readable without a
  running gateway).** Git bundles per repository, plus JSON and NDJSON in the
  vocabulary `RepositoryManifest` already uses. `git clone` opens the content;
  `jq` and a text editor open everything else. The export reads through the
  existing `GitStorage` seam, so the two storage backends produce comparable
  artefacts and no new method appears on the seam.
- **Secrets and grants stay behind (GW_0208 — Credentials and grants never
  appear in an export).** No token material, no `marketplaces.webhook_secret`,
  no `webhook_subscribers.secret`, no audit-sink cursors, no importable role
  grants. Enforced by negative tests over the produced artefact, not by review:
  the exclusion is a property of the bytes on disk.
- **Revocation and retention survive the trip (GW_0209 — Revocation and
  retention are stated in an export, never omitted).** Revoked and rejected
  snapshots are not filterable out — an omitted revocation reads, at a
  destination, as content that was never withdrawn. Retention-reclaimed content
  is stated as such, so a healthy estate does not verify as a corrupt archive.
- **Attestation travels with content (GW_0210 — Approval attestation travels
  with the content it attests to).** Snapshot rows, vetting runs, verdicts,
  findings, waivers, overrides, closures, and — at estate scope — a ledger copy.
  `ingested_by` and `decided_by` stay distinct, because collapsing them destroys
  the four-eyes evidence. Every attestation is namespaced under the instance that
  made it, so no field in the format reads as a local approval.
- **The export proves itself (GW_0211 — An export verifies itself before it is
  reported complete).** `StorageMigration`'s discipline, inherited: re-read the
  written bundle from the file and compare its resolved refs and symbolic links
  against a fresh read of the source, then refuse to report success on any
  disagreement, naming what disagreed.
- **And can be proved again later (GW_0212 — An export can be verified
  offline).** A verify mode over an artefact directory that opens no database and
  reads no configuration — checksums, bundle integrity, every attested SHA
  present — plus a documented by-hand equivalent, because an archive that
  outlives the product must be checkable after the product is gone.

## Capabilities

### New Capabilities

- `estate-export`: producing a portable, self-verifying artefact of a
  marketplace or a whole estate — content and the attestations that give it
  meaning together, readable without the gateway, carrying no secret and no
  grant.

### Modified Capabilities

_None._ Nothing about ingestion, vetting, approval, serving or retention
changes. The export is a read.

## Out of scope (named, so the boundary is explicit)

- **Import, entirely.** ADR 0014 sets the rules an import must obey and
  recommends not building it in this increment. No requirement id here covers
  it, and the format is designed so the unsafe version cannot be written easily
  later.
- **Signatures.** `checksums.txt` detects corruption, not forgery. Signed
  provenance stays where ADR 0005 put it.
- **A backup or restore feature.** The disaster-recovery answer stays a database
  backup plus a storage copy, or `StorageMigration` between backends. The
  documentation must say so.
- **Exporting configuration.** External vetting connectors, retention policy,
  the forge mirror and IdP mappings are configuration, and configuration holds
  credentials.
- **The virtual catalog.** It is a projection the destination rebuilds.
- **Scheduling, retention or rotation of export artefacts**, and any portal UI.
  An export is an operator act.

## Impact

- **DB**: none expected. The export is a read across existing tables. If an
  export-run record proves necessary it is additive, and `V1__init.sql` is
  amended in place per repo convention.
- **Backend**: a new `dev.skillsgateway.server.export` package. Reuses
  `GitStorage`, the `StorageMigration` comparison shape, and
  `AuditExportService`'s NDJSON line format rather than restating any of them.
- **API**: to be settled in implementation — an operator command is the minimum;
  an administrator-only endpoint would be additive if it lands.
- **Trust boundary**: the export is a **disclosure** surface, not an inbound one.
  Adversarial tests are still part of the definition of done, aimed at the
  exclusion list: an artefact is inspected for secret material rather than the
  code being read for care. The `.claude/skills/old-coder` discipline applies.
- **Docs** (same PR as implementation): a new guide, `reference/configuration.md`
  if properties land, `concepts/trust-boundaries.md`, and a cross-reference from
  `guides/storage-backends.md` distinguishing an export from a migration and from
  a backup.
