# ADR 0022 — Contributor license agreement and commercial terms

*Accepted, 2026-10-19. Amends ADR 0002 ("License: Apache-2.0").*

Not legal advice.

## Context

ADR 0002 chose Apache-2.0 and left a commercial or dual license "possible
later", keeping copyright consolidated through the DCO and deferring a CLA
"when outside contributors appear". The maintainer now wants the route to a
commercial offering written down before that happens, matching the model of
the maintainer's other project, ShotLedger.

At this point Jimisola Laursen is the only human author: every other commit
is a Renovate dependency bump. No outside contribution has been merged.

## Decision

1. **The public license stays Apache-2.0.** Released versions stay under it
   for good (its grants in sections 2 and 3 are irrevocable), and so do
   future ones unless a later ADR changes that.
2. **The copyright holder also offers Skills Gateway under commercial
   terms.** Jimisola Laursen may license Skills Gateway's own code under
   other terms, including proprietary ones (a closed edition, add-ons,
   warranties, indemnities, support), alongside the public license.
   Third-party components and vendored data keep their own licenses. This
   rests on sole authorship today and on the CLA for later contributions.
   The NOTICE copyright line now reads "Jimisola Laursen and the Skills
   Gateway contributors", since the CLA is a license, not an assignment.
3. **Outside contributions come under a CLA as well as the DCO.** `CLA.md`
   is the Harmony Individual CLA with outbound Option Five, the same text
   ShotLedger uses: the contributor keeps their copyright and lets the
   maintainer license the contribution "under any license, including
   copyleft, permissive, commercial, or proprietary licenses", provided it
   is also offered under the license in force on the submission date. The
   DCO stays, and its check stays required.
4. **No price, product or paid tier is decided here.**

## Why not the alternatives

- **Apache-2.0 alone (ADR 0002 as written).** Apache-2.0 already lets anyone,
  the maintainer included, build proprietary products on the code, and its
  section 5 licenses each contribution to the licensor under Apache-2.0
  unless a separate agreement says otherwise. What it does not give is a
  clean grant from each contributor to relicense their work, for example
  to move later versions to a copyleft or source-available license, or to
  sell terms that drop Apache's notice obligations. A CLA added after
  outside contributions arrive would not cover them.
- **AGPL plus commercial licenses, as ShotLedger does.** That makes a
  commercial license worth buying, but it changes the license of an
  enterprise product whose buyers often refuse AGPL software. The CLA keeps
  that move open as a later decision.

## Consequences

- `LICENSE` is unchanged. `README.md`, `NOTICE`, `CONTRIBUTING.md`, the
  pull-request template and ADR 0002 now say that commercial terms exist and
  that contributors agree to `CLA.md`.
- An outside pull request is not merged until its author has agreed to the
  CLA. Until a CLA check runs on pull requests, agreement is a comment on the
  pull request: "I have read CLA.md and agree to it".
- Because Apache-2.0 is permissive, a commercial license mainly sells what
  the public license leaves out: warranty, indemnity, support, and any
  closed code built on top. The name, any trademark, and any hosted service
  remain the real moat.
- Not reviewed by a lawyer. Before the first commercial deal, have one check
  `CLA.md`, the NOTICE wording and the commercial terms themselves.
- Open: whether commercial terms are offered by Jimisola Laursen personally
  or by a company (assigning copyright to a company needs a written
  assignment, and CLA §6.3 binds the assignee to the CLA).
