## 1. Requirement and traceability

- [x] 1.1 Add GW_INGEST_0033 — The marketplace read names the commit the facade
      serves to `docs/reqstool/requirements.yml`: the system shall report, on the
      marketplace read, the commit its facade is currently serving, read from the
      published repository's served reference, and shall report its absence where
      the marketplace serves nothing — distinctly from which of its snapshots are
      approved.
- [x] 1.2 Confirm the id is free in `requirements.yml` and in every in-flight
      `openspec/changes/`.
- [x] 1.3 Add SVC_GW_INGEST_0033.
- [x] 1.4 Add GW_APPROVAL_0018 — An approval control is presented only below the
      evidence it rests on, with SVC_GW_APPROVAL_0018. (Originally planned as an
      extension of GW_AUTH_0047, which is the build-version requirement and has
      nothing to do with approval; moved to its own requirement under
      `snapshot-approval`, where a trust-boundary property belongs.)

## 2. The served commit

- [x] 2.1 `MarketplaceView` gains `servedSha`, read the way the adoption read
      reads it, null when nothing is served.
- [x] 2.2 Java test: a marketplace with an approved snapshot reports its sha; the
      same marketplace after revocation reports null **while the approved row
      still exists** — that divergence is the whole point of the field.
- [x] 2.3 Regenerate `openapi.json` and `types.gen.ts`; `OpenApiContractTests`
      must pass.

## 3. The delta line

- [ ] 3.1 `lib/snapshot-delta.ts`: pure assembly of files changed, lines added and
      removed (counted from the unified diff text) and skill counts (from
      `content-diff.summary`).
- [ ] 3.2 Unit-test it directly: an added file, a removed file, a modified file,
      a binary entry with no diff text, a truncated diff, no baseline at all, and
      the empty case.
- [ ] 3.3 It must say when it is counting a **cut** set rather than reporting a
      confident total.
- [ ] 3.4 `components/snapshot-delta.tsx` renders it, skill counts leading.

## 4. The card

- [ ] 4.1 `components/snapshot-card.tsx`: identity → delta → tabs → decision, in
      that order, with the order asserted by a test rather than left to review.
- [ ] 4.2 Tabs: Contents, Diff, Vetting, Inventory, Provenance. Vetting is a tab,
      not an unconditional block — this is the change that removes the 518px.
- [ ] 4.3 Tab and path derive from the query string, never from component state
      alone, so a link restores both.
- [ ] 4.4 Decision row: Approve and Reject at the foot. Approve disabled with its
      reason when vetting blocks; the administrator override keeps its own
      control and dialog.
- [ ] 4.5 The open card names the held snapshots that approving it would
      supersede.

## 5. The page

- [ ] 5.1 Three sections: Awaiting decision (list, newest first, only the newest
      open), Serving (one line), Earlier snapshots (collapsed count).
- [ ] 5.2 Empty states: nothing awaiting collapses to a line rather than
      vanishing; serving nothing says so rather than showing the newest approved.
- [ ] 5.3 `snapshot-preview.tsx` is retired into the card's Contents tab.
- [ ] 5.4 `/marketplaces/:name/snapshots/:id/files` keeps working, rendering the
      same components — a thin wrapper, no duplicated logic.

## 6. Tests (the decision surface is a trust boundary)

- [ ] 6.1 Prove each test in this section fails before the code exists.
- [ ] 6.2 **Approve is never rendered above the evidence.** Assert document order
      in the card, not merely that both exist.
- [ ] 6.3 **A blocked snapshot's Approve is disabled and says why** — never fails
      on press.
- [ ] 6.4 **Two snapshots awaiting**: both listed, newest open, supersession
      named.
- [ ] 6.5 **Serving nothing while approved rows exist**: the page says nothing is
      served rather than naming one.
- [ ] 6.6 Deep link with `?snapshot=&tab=&path=` restores all three; the e2e spec
      for GW_INGEST_0032 is updated to drive the card and must still pass.
- [ ] 6.7 Page height: assert the rendered page with N snapshots does not grow
      linearly — a regression test for the thing this change exists to fix.

## 7. Documentation (same PR)

- [ ] 7.1 `reference/portal.md`: the three sections, the delta line, the tabs,
      where the decision sits and why.
- [ ] 7.2 `reference/api/marketplaces.md`: `servedSha` on the marketplace read,
      and that it differs from "newest approved".
- [ ] 7.3 `guides/approving-snapshots.md`: the reviewer's path through the new
      card, and supersession.

## 8. Gates and harness

- [ ] 8.1 `/impeccable audit` and `harden` on the rebuilt page. No `critique` —
      this rebuilds an existing page rather than adding one.
- [ ] 8.2 Run all gates fresh after the last edit and write `evidence.md`,
      including a re-measurement of page height against the 3,220px baseline.
- [ ] 8.3 Screenshot the rebuilt page from the live instance, light and dark, for
      the PR.
