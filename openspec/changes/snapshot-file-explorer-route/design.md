## Context

See `proposal.md` — Why. What shapes the approach here is that the backend is
already done: `GET /api/v1/snapshots/{id}/files` returns the flat path listing
with a `truncated` flag, `/file?path=` returns one blob with `binary`,
`truncated` and `size`, and `/diff` returns every changed path against the served
tip with a per-path unified diff. All three are gated by
`requireApproverOfSnapshot`. Snapshot metadata (sha, state) is already carried in
the `MarketplaceView` the portal loads for the detail page; there is no
`GET /snapshots/{id}`, and this change does not add one.

The two questions issue #448 deferred to this design are answered below under
Decisions: the listing cap stays at 2000, and the tree is assembled in the
browser.

## Goals / Non-Goals

**Goals:**

- A URL that identifies a snapshot and a path within it, and survives being
  pasted to a second approver.
- Each pane owns its scroll; the page itself does not scroll.
- Every bound the API already imposes is a rendered state, not a silence.

**Non-Goals:**

- No new endpoint, no change to any existing endpoint's shape, no change to
  authorization.
- Not a code editor: no syntax highlighting beyond what `MarkdownView` and a
  `<pre>` already do, no line linking, no blame. Those are separate arguments to
  make, not consequences of this one.
- Not a general file browser for served content. This is the reviewer's view of a
  held snapshot.

## Decisions

### The tree is assembled client-side; no new endpoint

The flat listing is capped at 2000 paths, each a short string — a few hundred
kilobytes at the very worst, and the browser is already fetching and rendering
all of them today. Grouping them into a nested structure is a `reduce` over an
array that has already arrived.

*Alternative: a server-built tree, with per-directory truncation.* Rejected for
now. It buys one real thing — truncation expressed per directory rather than once
for the snapshot — at the cost of a new endpoint, new DTOs, a new spec surface
and a second representation of the same data to keep in step. The stop rule asks
what is being removed; this would add. If a real marketplace ever exceeds 2000
paths in a way that matters, the per-directory truncation argument can be made
then, with evidence rather than anticipation.

### The 2000-path cap stays

It is a defence against a hostile upstream weaponising the reviewer's browser,
and that argument does not weaken because the surface got better — a tree of
200 000 paths is not more reviewable than a list of 200 000 paths. What changes
is that truncation becomes legible: the filter reports "N matching of 2000
listed, and the listing is cut", which tells the reviewer both that their search
was answered and that the answer is over an incomplete set.

### The per-file diff comes from the existing whole-snapshot `/diff`

`/diff` already returns a `diff` string per changed path. The page fetches it
once per snapshot and indexes the entries by path, so "diff this file against
served" is a lookup, not a request. A file with no entry is unchanged, and the
pane says so rather than showing an empty diff.

### Removed paths are merged into the tree, marked

A path deleted relative to the served commit is in `/diff` but, by definition,
not in `/files`. Showing only the snapshot's own paths would hide deletions from
the one surface built for reading the change. Such paths are merged into the tree
as entries marked `removed`, selectable, with a file pane that shows the removal
rather than a blob — there is no blob to fetch, and requesting one would 404.

### The server has to know the route exists

`SpaController` forwards a fixed list of client routes to `index.html`. A route
absent from it is invisible for as long as the browser stays inside the
application — the router answers without a request — and 404s the first time
someone opens it cold.

This was found by the real-browser suite reloading a deep link, after every
jsdom test passed: those drive an in-memory router, which never asks the server
anything. So the list is no longer maintained by hand against memory —
`SpaRoutesTests` parses the router's own `path:` declarations and asks the
running gateway for each one.

### `:name` in the path is context, not authority

Authorization is by snapshot id, server-side, exactly as today. The marketplace
name in the route gives the page its breadcrumb and back link, and lets the
header show the sha and state from the already-loaded `MarketplaceView` with no
extra request. A snapshot id that is not in the named marketplace renders the
page's not-found state; it is a wrong link, not a security decision, and the
gateway would refuse the reads regardless of what the segment said.

### The selected path lives in the query string, not the route path

`?path=skills/foo/SKILL.md` rather than a splat segment. A path containing `/`
is the normal case, so a splat would need encoding rules that the query string
already has; and a missing `path` is then simply "the explorer, nothing
selected", which is a legitimate state to link to.

Expansion state is **not** in the URL. It is derived: every ancestor directory of
the selected path is open, plus whatever the reviewer has since toggled, held in
component state. Two people opening the same link see the same file with it
revealed, without the URL carrying UI bookkeeping.

### The detail page loses the workspace and keeps the glance

`SnapshotPreview` stops being a two-pane workspace and becomes the inline hint it
can actually fit: the quick-open paths it already computes, and an **Inspect
contents** link to the route. The Files/Diff toggle is removed there — the new
surface does both at once, and keeping a second, worse copy of it on the detail
page is the outcome the stop rule exists to prevent.

## Risks / Trade-offs

- **A ninth route, and route count is a thing the project counts.** → It replaces
  a surface rather than adding one: the detail page's preview workspace is
  removed in the same change, and no new capability, endpoint, config leaf or
  Spring context appears.
- **The whole `/diff` response is fetched with the listing, not on demand.** → It
  has to be: the removed paths it carries are part of the tree, so deferring it
  would mean drawing a tree that is missing entries and then changing it under
  the reviewer. It is the same single request the current Diff tab already
  makes, against the same cap.
- **The two route lists — the router's and the server's — can drift again.** →
  They cannot drift silently: the test derives one from the other rather than
  restating it, and asserts it parsed a plausible number of routes so a silent
  zero cannot make it pass forever.
- **Deep links rot when a snapshot is deleted or purged (retention).** → The page
  renders the not-found state, which is the honest answer; a link to a purged
  snapshot should not resolve to anything else.
- **A reviewer may read the filter as searching the whole snapshot.** → The
  count is always phrased against the listed set and states when the listing is
  cut, so the filter never implies completeness it does not have.
