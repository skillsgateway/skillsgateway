# Tasks — fix-audit-records-wanted-snapshot-ref

## 1. Establish the defect and its blast radius before changing anything

- [ ] 1.1 Confirm from the code, not from the issue, that `ref` is the only wrong
      column and `AuditingPreUploadHook.onSendPack` the only wrong site. Record
      in `design.md` which ledger readers touch `ref` (none, outside the export
      and the portal's schema-less table).
- [ ] 1.2 Settle the issue's open question: does adoption derive from `ref`?
      Enumerate every `FetchLogRepository` query and its grouping columns.
      Answer belongs in `design.md` whichever way it comes out.
- [ ] 1.3 Write the failing real-client test: approve snapshot A, approve
      snapshot B so A is superseded, fetch A by name through the facade, assert
      the `upload-pack` row for A names `refs/snapshots/A`. **Prove it red**
      before any production edit, and paste the failure into `evidence.md`.

## 2. The requirement (GW_0154 / SVC_GW_0154)

- [ ] 2.1 Add `GW_0154` to `docs/reqstool/requirements.yml` — the fetch ledger
      records the advertised ref a transferred want resolves to.
- [ ] 2.2 Add `SVC_GW_0154` to `docs/reqstool/software_verification_cases.yml`
      covering the superseded snapshot, the current tip, and the ambiguity rule.

## 3. Resolve the want against the advertised set

- [ ] 3.1 Add a package-private static resolution function to
      `GitFacadeConfiguration`: advertised map + want → ref name or `null`.
      Order: main's tip, then a `refs/snapshots/*` tip, then `null`.
- [ ] 3.2 Call it from `onSendPack` in place of the `SERVED_REF` constant.
      Leave the `info-refs` entry and the `SERVED_REFS` filter alone.
- [ ] 3.3 Annotate the hook with `@Requirements({"GW_0008", "GW_0154"})`.
- [ ] 3.4 Turn 1.3 green.

## 4. Prove the tests discriminate

- [ ] 4.1 Unit-test the resolution function directly: the ambiguous case
      (main's tip and a snapshot ref on the same commit) resolves to
      `refs/heads/main`; a want matching no advertised ref resolves to `null`;
      `HEAD` is never the recorded name.
- [ ] 4.2 Real-client test that an ordinary clone still records
      `refs/heads/main` — the existing `SVC_GW_0008` assertion must keep
      passing, unweakened.
- [ ] 4.3 Mutate the resolution to always return `SERVED_REF` and confirm the new
      tests fail; restore. Record the mutation result in `evidence.md`.

## 5. Documentation, in this PR

- [ ] 5.1 `docs/manual/reference/api/audit.md` — the `ref` field row, the
      `upload-pack` event row, and the incorrect `"ref":null` example payload.
      State that rows predating this change record `refs/heads/main`.
- [ ] 5.2 `docs/manual/reference/git-facade.md` — the auditing section states
      what an `upload-pack` entry names and the tip-wins rule.
- [ ] 5.3 `docs/manual/concepts/snapshots-and-ledger.md` — the `ref` row of the
      ledger entry table.

## 6. Gates and archive

- [ ] 6.1 One fresh run of every gate after the last edit; paste real tails into
      `openspec/changes/fix-audit-records-wanted-snapshot-ref/evidence.md` with
      the commit SHA.
- [ ] 6.2 Archive the change as the final commit of the PR.
