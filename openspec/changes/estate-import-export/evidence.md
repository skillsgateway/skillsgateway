# Evidence: estate-import-export

**This change is not implemented.** It is a design artefact: a **proposed** ADR
0014 and an OpenSpec proposal whose tasks are all unchecked. There is no Java,
no TypeScript, no schema change and no test in it, so the gates that exercise
those things have nothing here to exercise.

That is stated rather than glossed, because an evidence report that lists gates
it did not run is worse than one that says why.

**Commit under test:** `fc0fd8cc284a29595b8f22360476ed276e32805a`
(`docs(decisions): propose ADR 0014 — estate export, and an import that can only
fill quarantine`), on `origin/main` at `331cf68`.

## What was run

Both gates were run fresh, in order, after the last edit to the branch's
content.

### `openspec validate --all --strict`

```console
$ openspec validate --all --strict
✓ change/admin-vetting-override
✓ spec/adoption-reporting
✓ spec/audit-export
✓ change/audit-ledger-vetting-detail
✓ spec/auth
✓ spec/continuous-revetting
✓ spec/declarative-estate
✓ change/estate-import-export
✓ change/external-vetting-connectors
✓ spec/forge-mirror
…
✓ spec/virtual-catalog
Totals: 32 passed, 0 failed (32 items)
```

### `mkdocs build --strict`

```console
$ mkdocs build --strict
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: …/site
INFO    -  Documentation built in 4.54 seconds
```

No warnings, so no nav or link failures. The only docs-site file this change
touches is `docs/manual/reference/decisions.md`, which gains the ADR 0014 index
entry. The ADR itself lives in `docs/decisions/`, which is outside `docs_dir` by
design, so no nav entry is needed and none was added.

No retries were performed. Neither command touched a container runtime.

## What was deliberately not run, and why

| Gate | Why not |
| --- | --- |
| `./mvnw clean verify` | No Java or TypeScript changed. The branch's diff is three Markdown files and one Markdown edit. |
| `pnpm test:stories` | No portal change. |
| `pnpm e2e` | No portal change. |
| `reqstool status local -p docs/reqstool` | No requirement entry was added — see below. Nothing in `docs/reqstool/` changed, so the gate's inputs are byte-identical to `main`. |

## Why no requirement ids were added yet

GW_0206 through GW_0212 are **reserved and used in the spec delta**, but they are
deliberately **not** in `docs/reqstool/requirements.yml` or
`software_verification_cases.yml`. A requirement with no implementation carries
no `@Requirements` annotation and no SVC test, and reqstool would report it
uncovered — turning the traceability gate red on `main` for a feature nobody has
started. They enter in the implementing PR, which is task 1.1 of `tasks.md`.

The ids were checked as free against this branch and against `origin/main`
(`grep -rn 'GW_020[6-9]\|GW_021[0-2]' docs/ src/` matches nothing outside this
change's own spec delta); the highest allocated id on `main` is GW_FACADE_0023.

## What this change does not claim

- It does not claim the design is right. It claims the trade-offs are written
  down, with the rejected options and the reasons, so the decision can be made
  rather than defaulted.
- Four questions in the ADR are recorded as **unsettled**, with the evidence
  that would settle each. The most consequential is whether `git bundle`
  round-trips symbolic `HEAD` and the `refs/snapshots/*` namespace — the same
  class of defect `StorageMigration` already hit. That is task 2.1, and it must
  be answered before the format is fixed.
