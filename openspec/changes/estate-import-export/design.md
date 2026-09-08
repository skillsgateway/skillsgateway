# Design: estate-import-export

> Not implemented. This is the shape an implementation would follow if
> [ADR 0014](https://github.com/skillsgateway/skillsgateway/blob/main/docs/decisions/0014-estate-import-export.md)
> is accepted. The ADR carries the decision and its alternatives; this document
> carries the mechanics.

## Context

- `StorageMigration` copies every repository from one `GitStorage` to another,
  then re-reads **both** sides and compares resolved reference maps and symbolic
  links, refusing to report success on disagreement. Its `resolved` /
  `symbolic` split is load-bearing: treating `HEAD` as an ordinary reference
  dereferences it silently and grows a spurious `refs/heads/main` at the
  destination. Any export that writes refs into a new container inherits that
  trap.
- `GitStorage` exposes three roles (`QUARANTINE`, `HOSTED`, `PUBLISHED`),
  `marketplaces(role)` read off the storage rather than off the database, and
  `isServedRef` as the single definition of what the facade puts on the wire.
  `StorageMigration` is currently the only caller that walks roles generically —
  the export is the second.
- `RepositoryManifest` is already a self-describing JSON description of a
  repository: `version`, a `refs` map whose values are either a 40-character
  object id or `ref: <target>`, and pack descriptions. Reusing that vocabulary
  means the same field names mean the same thing in a bucket and in an archive.
- `AuditExportService.streamNdjson` already emits the ledger as NDJSON, one
  `FetchLogRepository.AuditEntry` per line, with `id` doubling as the
  de-duplication key. There must not be a second spelling of a ledger line.
- Retention soft-deletes (`deleted_at`, `purge_after`, state preserved) and then
  compacts, deleting the snapshot row and cascading its vetting runs, verdicts,
  findings, overrides and closure away. `fetch_log` is never pruned.
- The virtual catalog occupies `published/<catalog-name>` with no `marketplaces`
  row, so a naive walk over `marketplaces(PUBLISHED)` sees it as a marketplace.

## Goals / Non-Goals

**Goals**

- An artefact a plain git client and a text editor can read, with no gateway,
  no database and no configuration.
- Content and attestation in one artefact, as a property of the layout rather
  than of an operator remembering to take both.
- No secret and no grant on disk, provable by inspecting the artefact.
- Success is never the default outcome of having finished.
- A format that a future import cannot mis-read as a local approval.

**Non-Goals**

- Import. Signatures. A backup. Configuration export. The catalog. Scheduling.
  All named in the proposal's out-of-scope list.

## Decisions

### 1. The artefact is a directory, and the manifest is written last

```text
manifest.json
checksums.txt
ledger.ndjson                              estate scope only
marketplaces/<name>/
  registration.json
  published.bundle   published.json
  quarantine.bundle  quarantine.json       estate scope only
  hosted.bundle      hosted.json           estate scope, hosted origin only
  snapshots.ndjson  vetting.ndjson  waivers.ndjson  closures.ndjson
```

A directory rather than a single tar: it is inspectable in place, a single
repository can be pulled out of it with `cp`, and a partial write is visible as a
missing `manifest.json` rather than as a truncated archive. Whoever needs one
file can `tar` it themselves.

`manifest.json` is written **after** verification passes, so a directory without
one is by definition not an export. That is the same property `StorageMigration`
gets from refusing to report success, expressed in the filesystem so it survives
the process that produced it.

`checksums.txt` is `sha256sum`-format, so `sha256sum -c checksums.txt` is the
by-hand check. It is not a signature and the manifest says so in words, because
a file that looks like an integrity guarantee will be read as one.

### 2. Refs go into the bundle; the ref map goes into JSON as well

A git bundle carries the refs it was created with, and `git clone` on it works.
It does **not** carry a symbolic `HEAD` the way a clone of a repository does.
This is exactly the class of defect `StorageMigration` documents in its
`resolved`/`symbolic` split, and it is the first thing to verify rather than
assume — see the uncertainty in ADR 0014.

So `<role>.json` is normative for the reference map: concrete refs and symbolic
links, in `RepositoryManifest`'s spelling (`ref: refs/heads/…` for a symbolic
value). The bundle is normative for the objects. A reader restoring by hand
clones the bundle and then applies the JSON; a reader inspecting the archive
reads the JSON alone. The two are cross-checked by verification, so they cannot
drift silently.

### 3. Attestations are NDJSON, one line per row, instance-namespaced

NDJSON rather than one document per marketplace: the estate ledger of a real
deployment must stream, `grep` is a legitimate tool on an archive, and it is
already the shape the audit export produces.

Every attestation line carries the id of the instance that produced it. There is
no bare `"state": "approved"` at the top level of anything — the state is a
property of a named foreign instance's record, so the plain reading of the file
is *"instance X approved this"*, never *"this is approved"*. This constrains the
format now precisely because import is deferred: the cheap, wrong importer is
the one that reads a field called `state` and writes it.

`snapshots.ndjson` carries `sha`, `upstream_sha`, `state`, `violation`,
`created_at`, `ingested_by`, `decided_by`, `decided_at`, `revoked_at`,
`revoked_by`, and an explicit content **disposition**:

| Disposition | Meaning | What the bundle holds |
| --- | --- | --- |
| `present` | live snapshot | its objects |
| `reclaimed` | soft-deleted under retention, restorable until `purge_after` | nothing, deliberately |
| `ledger-only` | known from `fetch_log`; the row was compacted away | nothing, and no vetting evidence either |

`ledger-only` exists because on any estate with retention enabled it is most of
the history. Without it an archive presents a truncated past as a complete one.

### 4. Verification is a second, independent read — twice, then offline

Modelled directly on `StorageMigration.migrateOne`:

1. Write the bundle and the JSON.
2. **Re-open the bundle from the file**, resolve its refs, and compare against a
   fresh read of the source repository through `GitStorage` — the same
   comparison `StorageMigration.compare` performs, extracted so there is one
   implementation rather than two.
3. Re-read every attestation file **from disk** and check each attested SHA with
   disposition `present` is reachable in the bundle that should hold it, and that
   each `reclaimed` / `ledger-only` line has no objects claiming to be there.
4. Report in the shape of `StorageMigration.Report`: a per-repository result
   with a mismatch list, a whole-run `verified()`, and a `refusal()` that names
   every repository that did not survive. Only then write `manifest.json`.

The offline mode repeats 3 and adds `checksums.txt` and bundle integrity, with
no `GitStorage` and no database in the picture at all. It is the mode that has to
still work in five years.

### 5. What the walker must skip, and why it is a decision rather than a filter

The walk is over `GitStorage.marketplaces(role)` — storage, not the database,
following `StorageMigration`'s reasoning that an export should carry what is
actually there. Two adjustments:

- **The catalog is skipped by name.** It has no `marketplaces` row, it is a
  projection, and a stale copy at a destination would assert a catalogue that
  does not match what is served there.
- **A repository with no database row is carried, and flagged.** That is the
  case `StorageMigration` deliberately supports and an archive should too: an
  orphan is evidence, and dropping it is how an export quietly loses something.

### 6. Exclusion is tested against the artefact, not against the code

The exclusion list is the part most likely to rot, because it is a list of
things *not* to do and every new column is a chance to forget. So the test reads
the produced directory and asserts that no file contains any known secret
material — the token hash, the marketplace webhook secret, the subscriber
secret — seeded with distinctive values first. A test that greps the artefact
keeps working when a column is added; a test that checks a mapping method does
not.

## Risks / Trade-offs

- **Bundle size at estate scale is unmeasured.** Quarantine accumulates
  everything retention has not reclaimed. Mitigation if it bites: per-snapshot
  bundles, at a cost in file count. Measurement first.
- **An estate-scope export puts unreviewed content in a file.** That is
  deliberate — an archive that cannot show what was rejected cannot answer an
  audit — but it is a new place hostile content leaves the gateway. Mitigated by
  labelling in the manifest and by defaulting marketplace scope to published
  content only.
- **Findings are connector-authored text.** If a secret-scanning connector
  quotes what it matched, exporting findings exports secrets. This is a
  pre-existing disclosure question — those findings are already in the portal and
  the API — but the export is where it becomes a file somebody mails.
  Implementation must establish the answer before shipping findings.
- **A format is a commitment.** Import is deferred, so the format has to be
  import-safe before anyone has written an importer. That is the argument for
  instance-namespacing every attestation now rather than when it matters.

## Migration Plan

None. The change adds a read path; nothing existing behaves differently, and
there is no schema change expected.

## Open Questions

Carried from ADR 0014 and repeated here so implementation does not lose them:

1. Does `git bundle` round-trip symbolic `HEAD` and the `refs/snapshots/*`
   namespace? Settle with an export of a marketplace served from a branch other
   than `main`, verified by clone, before the format is fixed.
2. How large is an estate-scope export in practice? Measure before choosing the
   bundle granularity.
3. Can a vetting finding contain matched secret material?
4. Does an estate archive need quarantine *content*, or only quarantine
   *attestations*?
