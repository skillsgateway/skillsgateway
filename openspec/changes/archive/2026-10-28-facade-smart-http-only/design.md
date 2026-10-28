# Design: facade-smart-http-only

## Context

See proposal.md, Why. JGit's `GitServlet` installs `AsIsFileService.DEFAULT`
unless told otherwise. That service allows dumb file access because
`http.getanyfile` defaults to true. `AsIsFileService.DISABLED` refuses every
dumb route.

## Decisions

### D1 — `AsIsFileService.DISABLED` on both servlets

One setter call on each servlet. The smart routes go through the upload-pack
and receive-pack factories, which the as-is service does not affect.

Alternative: a servlet filter that refuses dumb paths. Rejected, because it
re-implements JGit's own path routing, and the setter is the switch JGit
provides for exactly this.

### D2 — The publish endpoint too

`/publish/**` reads only what the push scope already authorizes, so dumb HTTP
there discloses nothing new. Two reasons to disable it anyway:
- One protocol per endpoint is simpler to reason about.
- A dumb read never reaches the upload-pack factory, which is where any future
  control on that endpoint would be added.

### D3 — No garbage collection of withdrawn objects here

With the dumb routes gone, objects that no ref names are unreachable over HTTP:
upload-pack refuses unadvertised wants. Pruning them from the served object
database would be defence in depth, but it touches storage on both backends and
the timing of withdrawal, so it is a separate change.

## Risks / Trade-offs

- [A dumb-only client stops working] → None is supported. `git`, JGit and the
  agent CLIs all use smart HTTP.
