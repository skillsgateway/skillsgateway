## Context

See `proposal.md` — Why, and the wireframes it was drawn from. Three facts from
the running instance shape the approach, all measured rather than assumed:

- **3,220px for two snapshots**, of which **518px per card** is a vetting report
  nobody asked for. That block is the whole scaling problem.
- **Two snapshots can await a decision at once.** Ingest twice without deciding
  and both sit `held`. So "awaiting" is a list, not a slot.
- **Re-ingesting an unchanged commit is idempotent** — no duplicate snapshot — so
  the queue only grows when upstream actually moves.

And one gap: `MarketplaceView` carries no `servedSha`, so the page cannot
currently distinguish *being served* from *newest approved*.

## Goals / Non-Goals

**Goals:**

- Page height stops growing with snapshot count.
- The reviewer's task is the first thing on the page, and the decision sits with
  the evidence.
- "Serving" is a fact read from the facade, not an inference from state badges.

**Non-Goals:**

- **No new endpoint.** Everything the delta line needs is already served.
- **Not a redesign of the vetting report itself** — it moves into a tab unchanged.
- **Not the retirement of the file-explorer route**, which is a follow-up; see
  the decision below.
- Not pagination of history. A collapsed count is enough until an estate proves
  otherwise.

## Decisions

### Roles, not states

The three sections are named for what the snapshot *is to the reviewer*, not for
its `state` column. "Awaiting decision" is `held`, but the point is that it is
work; "Serving" is the commit the facade answers with, which is not the same
question as which rows are `approved`.

That distinction is why `servedSha` has to become a real field rather than a
front-end `find(s => s.state === "approved")`. The two answers diverge exactly
when it matters — a marketplace mid-revocation holds approved snapshots and
serves nothing — and the inference would confidently point at a snapshot nobody
can fetch.

*Alternative: read the adoption endpoint for `servedSha`.* Rejected. That read is
a windowed analytics report; using it to answer "what is live right now" borrows
a number from a different question and couples the detail page to the fetch
ledger.

### The delta line is assembled, not requested

`/diff` gives changed paths and per-path unified diff text; counting `+`/`-`
lines from that text is arithmetic on data already in the browser.
`/content-diff` already returns `summary` with per-status skill counts. So the
one-liner needs no endpoint, no DTO and no server change.

Line counts and skill counts answer different questions on purpose — lines say
how big the review is, skills say what is actually arriving — and a reviewer
wants the second. Both are shown; the skill counts lead.

### Tabs carry their state in the address

`?snapshot=<id>&tab=<name>&path=<file>`. Three consequences, all deliberate:

- GW_INGEST_0032 — Addressable snapshot file inspection is satisfied unchanged.
  Its requirement is that *one address names the snapshot and the file and
  restores both*; it says nothing about a separate page.
- A reviewer can send a colleague a link to a **tab**, not just a file — "look at
  the provenance of this one" is now expressible.
- Back and forward walk what the reviewer looked at, inside one page.

What would break the requirement is tab state living only in component memory,
so the tab is derived from the query string rather than mirrored into it.

### Approve sits below the evidence, because the rule says so

DESIGN.md: *"don't offer an approval control without the evidence above it."*
This is the rule that decides placement, and it rules out the header row however
convenient that looks. The card's order is therefore fixed: identity → delta →
evidence tabs → decision.

Blocked vetting disables Approve and states the reason beside it, per the
existing "never fail on press" rule. The administrator override keeps its own
control and its own dialog; it is not folded into the primary action.

### The card opens on Vetting

Closest to what the page does today — the verdicts are the first thing a reviewer
sees — while no longer rendering them for every snapshot at once. It is also the
evidence the approval gate actually reads, so opening anywhere else would put a
reviewer one click away from the thing their decision turns on.

*Alternative: open on the diff against served.* Tempting, because it sits
directly under the delta line and is the question a successor snapshot is really
asking. Rejected: it makes it possible to reach the decision row having never
seen a verdict.

### The delta leads with skills, not lines

`1 skill added, 1 modified · 3 files · +48 −7`. What is arriving comes first;
how big it is follows as context. A skill is not judged by line count, so leading
with `+48 −7` would lead with the weaker number — but it is kept, because it is
the only sense of review size on the card.

### Supersession is stated before it happens

Approving the newest held snapshot retires the older held ones. Today that is
invisible until it has happened. The open card names the snapshots it would
supersede, in the decision row, so the consequence is read before the press
rather than discovered after it.

### The file-explorer route stays, for now

Making the card self-sufficient turns `/marketplaces/:name/snapshots/:id/files`
into a second surface for the same job, and the owner has agreed to retire it.
It is not retired here.

The route becomes a thin wrapper rendering the same components the card's tabs
render, so there is no duplicated logic — only a duplicated entry point. Retiring
it is a one-line router change in a follow-up, once the card has actually
replaced it. Doing it inside this change would mean deleting a surface that
shipped a day earlier, in the same commit as a large restructure, which makes
both halves harder to review and harder to revert independently.

## Risks / Trade-offs

- **The restructure touches the page every other portal test drives.** → The e2e
  suite is the guard: its specs navigate this page for approval, revocation,
  waivers and preview, so a regression surfaces there rather than in review.
- **Collapsing history hides snapshots an operator may be looking for.** → The
  count is always visible and the section expands; nothing is removed, and
  retention already governs what exists.
- **A one-line delta can mislead when the diff is truncated.** → The listing and
  diff caps are already surfaced as explicit states; the delta line inherits
  them and says when it is counting a cut set.
- **Two surfaces for inspection until the follow-up lands.** → Accepted
  deliberately, and time-boxed to one change. The alternative was a larger,
  less revertible PR.
- **`servedSha` makes the detail read do a git lookup.** → The same lookup the
  adoption read already performs per marketplace; it is a ref read, not a walk.
