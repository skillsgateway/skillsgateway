# Evidence: edit-marketplace-upstream-url

- **Source state:** `71fc2adb` (last code edit); every result below is from one
  fresh run after it. Later commits touch only this file, `tasks.md` and the
  archive.
- **Tier:** 3 (registration trust boundary, concurrency), under
  `.claude/skills/old-coder`.
- **Spec approval:** the owner replied "continue" to the request to approve the
  spec as written in `d533d352`, and that was taken as approval of it, including
  the three decisions it flagged (the row lock against the ingest race, the
  editor becoming registrant, the estate converging before the first snapshot).
  Revised visibly after approval: `design.md` D7 (machine reach under
  `marketplaces:register`), added when `MachineApiRegistryTests` required the
  route to be classified.
- **Independent verification:** not performed. A declared downgrade: the
  adversarial pass is the author's own (the mutants and the forced race
  interleavings below).

## Gates

```console
$ ./mvnw clean verify                              # openjdk 25.0.1
[INFO] Tests run: 1075, Failures: 0, Errors: 0, Skipped: 9
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
[INFO] Total time:  07:25 min

$ (cd src/main/frontend && pnpm test:stories)      # node 26.10.0, pnpm 12.8.2
 Test Files  19 passed (19)
      Tests  99 passed (99)

$ (cd src/main/frontend && pnpm e2e)
  26 passed (1.4m)

$ reqstool status local -p docs/reqstool           # reqstool==0.12.0, as ci.yml pins it
333/333 complete · 0 incomplete · PASS

$ openspec validate --all --strict                 # @fission-ai/openspec@1.3.1, as ci.yml pins it
Totals: 31 passed, 0 failed (31 items)

$ mkdocs build --strict
INFO    -  Documentation built in 1.61 seconds
```

What happened on the way, as it happened:

- **First `clean verify`: 2 failures, both caused by this change and fixed in
  code, not in the tests.** `RoleEnforcementTests` derives every route from the
  route table and failed on the unclassified `PUT /marketplaces/{name}/url`; it
  is now listed among the admin-gated mutations it walks. `RefRefusalTests`
  deletes the marketplace row so the snapshot insert fails on its foreign key;
  the new URL guard read "no row" as "URL changed" and threw first. A missing
  row is now left to the foreign key, and the test is unchanged. The second run
  failed only on Checkstyle (`MissingSwitchDefault`), fixed by replacing the
  switch.
- **`pnpm test:stories` failed twice before passing**, on 9 story files at the
  import (`Failed to fetch dynamically imported module`), untouched files among
  them; no assertion failed (40/40 tests that ran passed). Two of the failing
  files passed when run alone. This is the known post-`clean` Storybook cache
  failure (`node_modules/.cache/storybook` and `.vite` were cleared first, `/tmp`
  held 4.1 GiB); the third run passed.
- **reqstool.** The locally installed CLI is an editable development build that
  prints no `PASS` line; the gate ran from a scratch venv holding
  `reqstool==0.12.0`. Its first run reported 26 incomplete because an ad-hoc
  two-file vitest run of mine had overwritten `test-results/vitest-junit.xml`;
  `pnpm test` regenerated it and the gate passed.
- **Leaked containers:** none after the gates.

## Spec → tests

| Scenario | Verified by |
| --- | --- |
| SVC_GW_INGEST_0066: change accepted (new URL, editor as registrant, forge metadata re-resolved, duplicate warning naming the other marketplace and not itself, one ledger entry with previous and new URL, next ingest succeeds) | `MarketplaceUrlChangeTests.an_administrator_corrects_the_url_and_becomes_its_registrant` |
| SVC_GW_INGEST_0066: unchanged URL changes and records nothing | `…an_unchanged_url_changes_nothing_and_is_not_recorded` |
| SVC_GW_INGEST_0066: disallowed scheme, credential (and not echoed), unreadable (502 with reason), empty URL each refused and change nothing | `…a_url_registration_would_refuse_is_refused_and_changes_nothing` |
| SVC_GW_INGEST_0066: non-admin 403 | `…a_caller_who_is_not_an_administrator_is_refused` |
| SVC_GW_INGEST_0066: snapshot (rejected counts) 409, upstream never contacted | `…a_marketplace_with_a_snapshot_keeps_its_url_and_the_new_upstream_is_never_contacted` |
| SVC_GW_INGEST_0066: hosted 400 | `…a_hosted_marketplace_has_no_upstream_to_change` |
| SVC_GW_INGEST_0066: removed and unknown names 404 | `…a_removed_or_unknown_name_is_not_found` |
| SVC_GW_INGEST_0066 / GW_APPROVAL_0010: the editor conflicts, the original registrant does not | `…the_editor_and_not_the_original_registrant_is_kept_from_approving` |
| SVC_GW_INGEST_0066.1: an ingest that fetched before the change records nothing, says why, and its pin is taken back | `MarketplaceUrlChangeRaceTests.an_ingest_that_fetched_before_the_change_records_nothing` |
| SVC_GW_INGEST_0066.1: a change waiting on an open snapshot insert is refused 409 (forced interleaving, waiter observed in `pg_stat_activity`) | `…a_change_waiting_on_a_snapshot_being_recorded_is_refused` |
| SVC_GW_INGEST_0066.1: a snapshot insert waiting on an open change records nothing (forced interleaving) | `…a_snapshot_waiting_on_a_change_being_made_is_not_recorded` |
| SVC_GW_ESTATE_0002: drift with a snapshot still fails and keeps the URL; drift without one is `updated`, unreadable upstream reported, ledger entry, reconciler as registrant | `EstateReconciliationTests.declared_marketplaces_face_the_same_registration_gate_as_the_api` |
| SVC_GW_INGEST_0067: the dialog, client rules (empty, whitespace, credential), server refusal shown, accepted URL shown | `marketplace-detail.test.tsx: an_admin_corrects_the_url_before_the_first_snapshot`; e2e `admin_corrects_a_marketplace_url_before_its_first_snapshot` |
| SVC_GW_INGEST_0067: with a snapshot, no control and the reason | `a_marketplace_with_a_snapshot_offers_no_url_correction_and_says_why`; e2e (after the ingest) |
| SVC_GW_INGEST_0067: declared marketplace, control unavailable with reason | `a_declared_marketplace_url_is_corrected_in_its_declaration`; story `Declared` |
| SVC_GW_INGEST_0067: non-admin sees no control | `a_user_who_is_not_an_administrator_is_not_offered_the_url_correction` |
| SVC_GW_INGEST_0067: a 409 mid-dialog withdraws the control | `a_snapshot_arriving_mid_correction_withdraws_the_control` |
| Must not: weaken an existing test | `RefRefusalTests` and the existing `SVC_GW_ESTATE_0002` assertions are unchanged; the estate test gained a snapshot in its drift case's setup (flagged at approval) and a new case |

## Failure model → layer

| Failure mode | Caught by |
| --- | --- |
| F1 a new URL skips a registration check | refusal test; mutants J1–J4 |
| F2 an edit after the first snapshot, or of a hosted marketplace | snapshot and hosted tests; J5–J7 |
| F3 old-URL content recorded under the new URL | the three race tests; J8, J9 |
| F4 a refused edit leaves a partial write | every refusal test asserts URL, registrant, forge and ledger unchanged |
| F5 the previous registrant can approve the editor's upstream | four-eyes test; J10 |
| F6 the estate converges what the API refuses, or refuses what it converges | estate test; J12, J13 |

## Mutation

`openspec/changes/edit-marketplace-upstream-url/mutants.sh`, run in four
`ONLY=` chunks at `71fc2adb`: **20/20 killed** (J1–J13 Java, U1–U7 portal),
each by the named test, each restore proven with `git diff --exit-code`.

Negative control: the same runner with a comment-only mutant
(`CTRL-comment-only`) reported `SURVIVED` and exited 1, tree clean.

Known limit: the 404 for a removed name is enforced twice (the name lookup, and
`deleted_at IS NULL` under the row lock). Each mutated alone is caught by the
other, so neither has a killing test; `a_removed_or_unknown_name_is_not_found`
passed before the route existed and is regression armour for the pair.

The portal tests were written after the component, so they never failed
before it existed; mutants U1–U6 (run before the first portal commit and again
here) are what show each one can fail.

## Other layers

- **Real execution:** the e2e test drives the packaged jar against PostgreSQL
  and the mock IdP: register, a refused correction, an accepted one, ingest,
  then the locked card.
- **Impeccable:** `impeccable detect` found nothing in the component or the
  page. The manual audit and harden pass found one gap, fixed: a 409 left the
  control on screen until a reload (U7 guards it).
- **Types and lint:** `tsc -b` and oxlint clean for the changed files (inside
  `mvnw verify`); Spotless and Checkstyle clean.
- **Coverage on changed lines:** not measured by a tool; the mutation run is
  the evidence that the changed lines are asserted on.
- **Property-based tests:** skipped; nothing here parses or has an algebraic
  invariant beyond what the scenario tests enumerate.
- **Supply chain:** no dependency added.
