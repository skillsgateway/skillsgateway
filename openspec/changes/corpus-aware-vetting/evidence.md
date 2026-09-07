# Evidence: corpus-aware-vetting

**This is a design-only change.** It adds a proposed ADR, an OpenSpec proposal,
and two documentation references. **No source file, no schema, no test and no
requirement is touched**, so the gates that exercise the build do not apply and
were deliberately not run — the container runtime was saturated by parallel work
at the time, and running them would have proved nothing about a change that
contains no code.

The evidence below is for the branch as pushed. It will be **replaced** by a
full-gate run when the change is implemented (tasks 9.1–9.2).

## What was run

Commit under test: see `git log -1` on `docs/corpus-aware-vetting-design`
(recorded in the PR body).

### `openspec validate --all --strict`

```
✓ change/corpus-aware-vetting
…
Totals: 32 passed, 0 failed (32 items)
```

### `mkdocs build --strict`

```
INFO    -  Cleaning site directory
INFO    -  Building documentation to directory: …/site
INFO    -  Documentation built in 3.38 seconds
```

Exit 0. The only output on stderr is Material for MkDocs' standing banner about
MkDocs 2.0, which is unrelated to this change and present on `main`.

No retries were needed for either command.

## What was not run, and why

| Gate | Not run because |
| --- | --- |
| `./mvnw clean verify` | No Java, resource, schema or `pom.xml` change. |
| `pnpm test:stories` | No frontend change. |
| `pnpm e2e` | No frontend or API change. |
| `reqstool status local -p docs/reqstool` | `docs/reqstool/` is **untouched** — verified by `git diff --stat origin/main -- docs/reqstool`, which is empty. GW_0194–GW_0198 are reserved but deliberately **not** written into `requirements.yml`: adding requirements with no implementation and no annotations would take the gate from `165/165 complete · PASS` to `165/170 · FAIL` on `main`. `tasks.md` §1 is what writes them, in the implementing PR. |

The reqstool baseline was confirmed on `origin/main` before the branch was cut:

```
INCOMPLETE (0)
165/165 complete · 0 incomplete · PASS
```

## Files changed

- `docs/decisions/0015-corpus-questions-are-approval-gate-preconditions.md`
  (new, status *proposed*)
- `docs/manual/reference/decisions.md` — index entry appended
- `docs/manual/architecture.md` — a paragraph under the threat table pointing at
  ADR 0015 and stating that T5 remains uncovered until it is implemented
- `openspec/changes/corpus-aware-vetting/` — `proposal.md`, `design.md`,
  `tasks.md`, `specs/{snapshot-facts,snapshot-approval,vetting-waivers,policy-rules,snapshot-vetting}/spec.md`,
  this file
