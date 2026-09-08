# Tasks: estate-import-export

> **Nothing here is done.** This change ships as a design artefact alongside a
> **proposed** ADR 0014. No task below has been started, and the requirement ids
> are reserved, not allocated — they are deliberately **not** in
> `docs/reqstool/requirements.yml` yet, because a requirement with no
> implementation and no annotation fails the reqstool gate for everyone on
> `main`. Task 1 is where they enter, in the implementing PR.

## 1. Requirements (SSOT first, in the implementing PR)

- [ ] 1.1 Add GW_0206 (estate and marketplace export as a portable artefact),
      GW_0207 (an export is readable without a running gateway), GW_0208
      (credentials and grants never appear in an export), GW_0209 (revocation and
      retention are stated in an export, never omitted), GW_0210 (approval
      attestation travels with the content it attests to), GW_0211 (an export
      verifies itself before it is reported complete) and GW_0212 (an export can
      be verified offline) to `docs/reqstool/requirements.yml`
- [ ] 1.2 Add SVC_GW_0206 … SVC_GW_0212 (GIVEN/WHEN/THEN) to
      `docs/reqstool/software_verification_cases.yml`

## 2. Settle the format's open questions before fixing it

- [ ] 2.1 Establish whether `git bundle` round-trips symbolic `HEAD` and the
      `refs/snapshots/*` namespace, with a published repository served from a
      branch other than `main`. Record the answer in the design (GW_0207)
- [ ] 2.2 Establish whether any shipped vetting connector can put matched secret
      material into `vetting_findings.message` or `location`; if it can, decide
      redaction before findings are exported (GW_0208)
- [ ] 2.3 Measure an estate-scope export of a realistic estate and choose bundle
      granularity from the number, not from taste (GW_0206)

## 3. The artefact format

- [ ] 3.1 `ExportManifest` — format version, instance id, produced-at, scope,
      gateway version, and the explicit exclusion list (GW_0206)
- [ ] 3.2 Repository ref-map serialization reusing `RepositoryManifest`'s
      vocabulary: concrete refs and `ref: <target>` symbolic values, with the
      `resolved` / `symbolic` split `StorageMigration` proved necessary (GW_0207)
- [ ] 3.3 Attestation line types, every one namespaced under the instance that
      produced it, with no top-level field whose plain reading is a local
      approval (GW_0210)
- [ ] 3.4 Snapshot content disposition — `present`, `reclaimed`, `ledger-only`
      (GW_0209)
- [ ] 3.5 Ledger lines emitted through the existing `AuditExportService` NDJSON
      shape rather than a second spelling (GW_0210)
- [ ] 3.6 `checksums.txt` in `sha256sum` format, with the manifest stating in
      words that it is not a signature (GW_0212)

## 4. The export itself

- [ ] 4.1 `EstateExporter` walking `GitStorage.marketplaces(role)` per scope;
      skips the virtual catalog by name; carries and flags a repository with no
      database row (GW_0206)
- [ ] 4.2 Bundle writing per repository role, streamed through a temporary file
      rather than through the heap, following `StorageMigration.copyObjects`'
      reasoning (GW_0206)
- [ ] 4.3 Attestation extraction: snapshots, vetting runs/verdicts/findings,
      waivers, overrides, closures, registration — with `ingested_by` and
      `decided_by` kept distinct (GW_0210)
- [ ] 4.4 Revoked and rejected snapshots are not filterable out of any export
      (GW_0209)
- [ ] 4.5 The exclusion list applied at the source of each extraction: no token
      material, no `marketplaces.webhook_secret`, no `webhook_subscribers.secret`,
      no audit-sink cursor, no importable role grant (GW_0208)
- [ ] 4.6 `manifest.json` written last, only after verification passes (GW_0211)
- [ ] 4.7 `@Requirements` annotations on the implementing methods

## 5. Verification

- [ ] 5.1 Extract `StorageMigration`'s comparison so the export uses one
      implementation rather than a second copy (GW_0211)
- [ ] 5.2 Verify-after-write: re-open each bundle from the file, compare resolved
      refs and symbolic links against a fresh `GitStorage` read (GW_0211)
- [ ] 5.3 Verify the attestations against the bundles: every `present` SHA
      reachable, every `reclaimed` / `ledger-only` line claiming no objects
      (GW_0209, GW_0211)
- [ ] 5.4 A refusal in the shape of `StorageMigration.Report.refusal()`, naming
      every repository that did not survive; success is never the default outcome
      of having finished (GW_0211)
- [ ] 5.5 Offline `verify` over an artefact directory: no database, no
      configuration, no `GitStorage` (GW_0212)

## 6. Tests

- [ ] 6.1 `EstateExportTests` (`@SVCs({"SVC_GW_0206", "SVC_GW_0207"})`): both
      scopes; a real `git clone` of a produced bundle; the JSON ref map matches
      the source; a marketplace served from a branch other than `main`
- [ ] 6.2 `ExportExclusionTests` (`@SVCs({"SVC_GW_0208"})`): seed distinctive
      secret values into `access_tokens`, `marketplaces.webhook_secret` and
      `webhook_subscribers.secret`, produce an export, and assert **no file in
      the artefact contains any of them**. Greps the artefact, not the mapper, so
      a newly added column cannot pass by being forgotten
- [ ] 6.3 `ExportRetentionAndRevocationTests` (`@SVCs({"SVC_GW_0209"})`): a
      revoked snapshot is present and marked revoked; a soft-deleted one is
      `reclaimed` with no objects; a compacted one appears as `ledger-only`; no
      option removes a revocation
- [ ] 6.4 `ExportAttestationTests` (`@SVCs({"SVC_GW_0210"})`): `ingested_by` and
      `decided_by` survive distinctly, including through a revocation; every
      attestation is instance-namespaced; the estate-scope ledger copy is the
      same line shape the audit export emits
- [ ] 6.5 `ExportVerificationTests` (`@SVCs({"SVC_GW_0211"})`): a tampered bundle,
      a truncated attestation file and a ref moved after the copy are each caught
      and named, and no `manifest.json` is written
- [ ] 6.6 `OfflineVerifyTests` (`@SVCs({"SVC_GW_0212"})`): verification of a
      fixture artefact with no Spring context, no database and no configuration;
      a corrupted checksum and a missing bundle both fail

## 7. Documentation (same PR as implementation)

- [ ] 7.1 A new guide: producing an export, reading one by hand
      (`git bundle verify`, `sha256sum -c`, `jq`), and verifying one offline
- [ ] 7.2 `concepts/trust-boundaries.md`: the export as a disclosure surface, and
      what deliberately does not cross it
- [ ] 7.3 `guides/storage-backends.md`: an export is not a migration and **not a
      backup**; cross-link all three
- [ ] 7.4 `reference/configuration.md` if any property lands
- [ ] 7.5 Add the guide to `mkdocs.yml` nav

## 8. Gates and archive

- [ ] 8.1 All gates green, `evidence.md` rewritten from one final fresh run
- [ ] 8.2 Archive the change
