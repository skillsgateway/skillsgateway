# Proposal: an unchanged re-vet writes two ledger rows, not one per vetter

## Why

Every run of the vetting chain appends one `vetting-verdict` row per vetter to
the audit ledger, re-vets included. The scheduled re-vet runs every approved
snapshot once a day, so a snapshot vetted by an n-vetter chain adds n verdict
rows, a `vetting-completed` row and a `revet-clear` row to the compliance ledger
every day to record that nothing changed. On the live instance 50 of 76 ledger
rows are `vetting-verdict`, almost all of them copies of the previous run's rows
with a new run id (#545). The
verdicts stay in `vetting_verdicts`, which is where the portal reads them from.
The attributable run that `GW_VETTING_0012 — Continuous re-vetting of approved
snapshots` asks for is the `vetting_runs` row plus the `vetting-completed` entry
(`run=`, `chain=`), and the outcome `GW_VETTING_0017 — Re-vetting and revocation
are audit-logged` asks for is the `revet-*` entry. The per-vetter copies add
noise, not evidence.

## What Changes

- **A re-vet writes a `vetting-verdict` row only for a vetter whose verdict
  changed** since the snapshot's previous chain run. "Changed" means the state,
  the finding count or the worst severity differs, or the vetter at its
  version (`name@version`) was not in the previous run's chain. No previous run
  means every verdict counts as changed.
- **`vetting-completed` on a re-vet gains `changed=<k>`**, the number of verdict
  rows the run wrote, so an omission reads as a statement rather than a gap. An
  unchanged re-vet writes exactly two rows: `vetting-completed` (`changed=0`)
  and `revet-clear` (or `revet-inconclusive`).
- **The ingestion run is unchanged:** one `vetting-verdict` per vetter plus
  `vetting-completed`, with no `changed=` field.
- **Unchanged:** every event keeps its name and meaning; `revet-violation`,
  `revet-violation-affected`, `snapshot-revoked`, `snapshot-unpublished` and
  `held-evidence-refreshed` are written exactly as before; the `vetting_verdicts`
  table still gets a row for every vetter on every run, so the portal and the
  REST vetting report see no change.
- Not **BREAKING** under `docs/manual/reference/compatibility.md` "The API
  contract": no OpenAPI or estate-schema change, no field removed. It changes
  what a SIEM receives, so the PR carries a release note: count runs by
  `vetting-completed`, not by `vetting-verdict`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `continuous-revetting`: `GW_VETTING_0017 — Re-vetting and revocation are
  audit-logged` gains the conditional-row rule and the `changed` count (revision
  bump), with two new verification cases, `SVC_GW_VETTING_0017.1` (an unchanged
  re-vet writes exactly the two rows) and `SVC_GW_VETTING_0017.2` (a changed
  re-vet writes the changed vetter's row and `changed=1`).
- `snapshot-vetting`: `GW_VETTING_0006 — Vetting actions are audit-logged` and
  `GW_VETTING_0022 — A vetting ledger entry is self-describing and correctly
  attributed` say "every vetter verdict" is appended to the ledger. Their wording
  is narrowed to the verdicts `GW_VETTING_0017` requires, so the requirement set
  does not contradict itself (revision bumps; their SVCs, which exercise the
  ingestion run, stand as written).

## Impact

- `VettingService.run` — compares each verdict with the previous run's and
  counts the changes; `VettingRepository` — reads the snapshot's latest finished
  run before a given one.
- `docs/reqstool/requirements.yml`, `software_verification_cases.yml`.
- Tests: two new SVC tests in `RevetTests` (existing shared context, no new
  Spring context). No existing assertion counts re-vet verdict rows.
- Docs: `reference/api/audit.md` event table, `guides/exporting-the-audit-ledger.md`,
  `guides/re-vetting.md`, `concepts/vetting.md`, `guides/adding-an-external-vetter.md`.
- Ledger volume: an unchanged daily re-vet writes two rows per approved snapshot
  instead of n + 2 for an n-vetter chain.
