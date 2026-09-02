# Tasks — fix-release-tag-after-approval

## 1. Prove the current order is what the requirement forbids

- [x] 1.1 Read `common-release-tag.yml` at the pinned SHA and record what it does
      on an existing tag, so the guide states behaviour that was checked rather
      than assumed. Result: it fails with `Tag '<version>' already exists` before
      creating anything.
- [x] 1.2 Confirm `SVC_GW_0108`'s existing assertions encode the old order
      (`needsOf("tag")` contains `checks`, `needsOf("approve")` contains `tag`),
      so the reorder cannot land without the test being changed with it.

## 2. Reorder (GW_0108 / SVC_GW_0108)

- [x] 2.1 `approve` needs `[prepare, checks]`; `tag` needs `[prepare, approve]`;
      `image`, `docs` and `package` need `[prepare, tag]`.
- [x] 2.2 `approve.environment.url` points at the run rather than at
      `needs.tag.outputs.url`, and its summary step stops referencing a
      prerelease that does not yet exist.
- [x] 2.3 Move the `approve` job above `tag` in the file so it reads in
      execution order, and rewrite the header comment that states the old
      rationale.
- [x] 2.4 `SVC_GW_0108` asserts the approval precedes the tag and that every
      publisher waits on the tag.

## 3. Report a partial release (GW_0108 / SVC_GW_0108)

- [x] 3.1 Add the `partial` job, guarded on `always()`, a successful `tag` and a
      `promote` that did not succeed.
- [x] 3.2 It writes a per-step table to the run summary and a warning
      annotation, and never fails the run.
- [x] 3.3 `SVC_GW_0108` asserts the job exists, depends on `tag` and `promote`,
      and survives a cancelled run.

## 4. Requirements and documentation

- [x] 4.1 `GW_0108` names the tag among the steps the approval precedes and gains
      the report obligation; revision `0.2.0`.
- [x] 4.2 `SVC_GW_0108` gains the matching assertions; revision `0.2.0`.
- [x] 4.3 `releasing.md`: the order diagram, "the approval decides whether the
      version exists", and a recovery section covering what each stopping point
      leaves, re-run support, fix-forward and clean-up.
- [x] 4.4 The `skillsgateway/.github` follow-ups are written up in `design.md`
      rather than implemented, since that repository is out of scope.

## 5. Gates

- [ ] 5.1 `./mvnw clean verify`
- [ ] 5.2 `reqstool status local -p docs/reqstool`
- [ ] 5.3 `openspec validate --all --strict`
- [ ] 5.4 `mkdocs build --strict`
- [ ] 5.5 `evidence.md` with the pasted tails and the commit SHA.
