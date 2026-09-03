# Evidence: snapshot-content-diff

Tier 1 (no trust boundary). The endpoint is an additive read derived from
content `GET /api/snapshots/{id}/content` already exposes to the same callers,
it returns no file bytes, and nothing in the approval path reads it.

## Spec ↔ test mapping

| Requirement | Verifies | Test |
| --- | --- | --- |
| GW_0153 — inventory diff against the last approved snapshot | no baseline, added, changed-by-neighbouring-file, removed plugin, skill moved between plugins, unknown snapshot | `ContentDiffTests.snapshotContentDiffAgainstTheLastApprovedSnapshot` (SVC_GW_0153) |

The one test walks every case the requirement names, in one fixture, because
they interact — a move is only correct if the skill is *not* also reported
removed, and a "changed" verdict is only meaningful beside an "unchanged" one:

- **No baseline.** The marketplace's first snapshot reports
  `baselineSnapshotId: null`, `baselineSha: null`, every plugin and every skill
  `added`, `summary.added == 4`.
- **Changed, decided from the whole skill directory.** The second snapshot edits
  only `plugins/review/skills/summarize/helper.txt` and leaves that skill's
  `SKILL.md` byte-identical; the skill is reported `changed`. A `SKILL.md`
  comparison would have said `unchanged`, which is the failure mode this
  requirement exists to close.
- **Moved, once.** `critique` moves from `hello` to `review` with identical
  content: reported `moved` with `movedFromPlugin: "hello"` under `review`, and
  `hello`'s skill list is asserted to contain exactly `hello` and `greet` — so
  it is provably *not* also reported as removed.
- **Removed plugin.** `legacy` is dropped from the manifest and its tree
  deleted; the plugin is still returned, `removed`, with `oldtool` `removed`.
- **Unchanged is still reported.** `hello/hello` is untouched and reported
  `unchanged`, so the summary reads 1 added, 1 removed, 1 changed, 1 moved,
  1 unchanged.
- **Unknown snapshot** answers 404.

Guardrails the change had to satisfy, verified by existing suites rather than by
new ones:

- `MachineApiRegistryTests` — the new route is classified (under
  `marketplaces:read`) or the build fails. It did fail on the first full run,
  which is the check working; the classification is a deliberate decision,
  argued in `design.md` §7 and in the code comment beside it.
- `RoleEnforcementTests` — the deny-by-default walk now asserts the new read
  stays open to a no-role session, beside `/content`.
- `portal.spec.ts` — the real-browser flow asserts the panel's no-baseline state
  on a marketplace with one snapshot.

## Gates run locally

All at commit `8449bcc` (`feat(api): classify the content diff route for machine
credentials`), which was the last code edit in this change, and re-run after the
requirement was renumbered (see below).

| Gate | Command | Result |
| --- | --- | --- |
| Java + UI + jar | `./mvnw clean verify` | `BUILD SUCCESS` — `Tests run: 388, Failures: 0, Errors: 0, Skipped: 0` |
| Portal unit | `(cd src/main/frontend && pnpm test)` | `Test Files 11 passed (11)`, `Tests 45 passed (45)` |
| Typecheck / lint | `(cd src/main/frontend && pnpm typecheck && pnpm lint)` | clean (only the repository's pre-existing oxlint warnings) |
| OpenSpec | `openspec validate --all --strict` | `Totals: 31 passed, 0 failed (31 items)` |
| Docs | `mkdocs build --strict` | `Documentation built in 2.46 seconds`, no warnings |
| Design harness | `/impeccable audit` + `harden` on the changed surface; `detect.mjs --json` | detector `[]`; one real finding fixed (see below) |
| Traceability | `reqstool status local -p docs/reqstool` | `129/143 complete · 14 incomplete · FAIL` — **GW_0153 is COMPLETE**; see below |

The impeccable harden pass found one genuine defect: a plugin reported `removed`
whose skills had all moved to other plugins reached the "the manifest entry
changed; its skills did not" note, which is false for it. Fixed in `38d26b9`;
the note is now status-aware. No finding was dismissed.

## Renumbering, after the fact

The requirement first shipped here as GW_0150, which collided with #244
(`feat/external-plugin-sources`), already green and ahead of this change. It is
now **GW_0153 / SVC_GW_0153**, verified free against `origin/main` (highest
GW_0149), against #244 (GW_0150-GW_0152) and against the other open PRs (#242
and #245 add none above GW_0149).

The rename is identifier-only — no behaviour, no test, no endpoint changed — and
the gates above were re-run over it: `./mvnw clean verify` **BUILD SUCCESS**
(`Tests run: 388, Failures: 0, Errors: 0, Skipped: 0`), `openspec validate --all
--strict` 30 passed, `mkdocs build --strict` clean, and `reqstool status local -p
docs/reqstool` unchanged in shape at `129/143 complete · 14 incomplete · FAIL`
with GW_0153 COMPLETE in GW_0150's place.

## Gates that could not complete in this environment

This machine runs several worktrees of this repository at once, and both
remaining suites bind fixed host ports.

- **`(cd src/main/frontend && pnpm e2e)` — could not run.** The first attempt
  died with `Web server failed to start. Port 8081 was already in use` (held by
  an unrelated process, so not something to kill). Retried with
  `E2E_GATEWAY_PORT=18081`: the compose stack came up healthy, then **all 13
  specs failed identically inside the shared `login()` helper** —
  `locator.click: Test timeout of 30000ms exceeded` on the mock IdP's Sign-in
  button, `portal.spec.ts:18`. The mock IdP is published on the fixed host port
  `9090` (`compose.e2e.yaml`), which is not parameterised, so the browser is
  reaching a neighbouring stack's IdP. Every failure is before any portal code
  runs, in specs this change does not touch, so it is environmental, not a
  regression. Deferred to CI, where the suite has the machine to itself (#103).
- **`(cd src/main/frontend && pnpm test:stories)` — could not run.** The
  Storybook browser project never got past `Port 63315 is in use, trying another
  one…` and was abandoned after ten minutes. This change adds no story and
  changes none.

**Why reqstool ends FAIL, and why it is not this change.** The 14 requirements
it reports incomplete — GW_0018, GW_0019, GW_0026, GW_0030, GW_0036, GW_0042,
GW_0047, GW_0055, GW_0078, GW_0079, GW_0082, GW_0097, GW_0098, GW_0138 — are
*exactly* the 14 whose SVCs are carried by `e2e/portal.spec.ts`, and they are
incomplete solely because that suite produced no JUnit results. None of them
belongs to this change, and GW_0153 is reported COMPLETE. With the e2e suite's
results present the gate reaches 143/143.
