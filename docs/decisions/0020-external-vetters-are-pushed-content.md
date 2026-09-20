# ADR 0020 — External vetters are pushed content; a signed fetch URL stays unbuilt

*Proposed, 2026-09-20.*

## Context

[ADR 0009 — The external vetting connector contract](0009-external-vetting-connector-contract.md)
settled how an operator's own vetter joins the chain: the gateway POSTs the
snapshot bundle and reads back a normalized verdict. It rejected the alternative
in one line — "Send a fetch handle instead of content. Rejected for v1:
quarantined content is not served, so the external service cannot pull it; the
gateway ships a bounded bundle. Revisit if bundles get large." This ADR is that
revisit, and it answers a question the one-liner left open: *large* is not the
only reason someone would want a handle.

What the push model actually sends is the **scannable** content. A file above
`max-file-bytes` arrives as `content: null, scanned: false`, and so does a file
that is not valid UTF-8 text. The whole bundle is capped by
`max-request-bytes` (5 MB by default, per vetter), and a snapshot over that cap
is an **error verdict**, which blocks — the gateway will not ship partial
evidence and call the answer real.

Three consequences, only one of which the ADR 0009 line anticipated:

1. **Size is tunable, so size alone is not the case.** `max-request-bytes` has a
   default, not a ceiling. An operator whose marketplace outgrew 5 MB of text
   raises it; the cost is memory and transfer, not a wall.
2. **Binary content is not reachable at all.** Every non-text file arrives with
   no content, by construction. A vetter whose job is to *detonate* a snapshot —
   run it in a sandbox and watch what it does — cannot be built on this contract
   at any cap, because the thing it needs to execute is exactly what the bundle
   omits.
3. **Tree shape is lost.** The bundle is a flat list of paths and content, not a
   working tree with modes, symlinks or an archive a sandbox can unpack.

So the question is not "push or pull for big snapshots". It is: **does the
gateway acquire a way for an outside party to read unapproved content?**

## Decision

**No. The gateway keeps pushing a bounded bundle, and no signed URL is built
until a named vetter exists that push provably cannot serve.**

The quarantine boundary is the product. "The quarantine repo is never served;
only `ApprovalService` publishes" is the sentence the whole architecture is
arranged around, and a signed URL is a way to serve quarantined content — to a
third-party endpoint, outside the approval gate, authenticated by a secret that
travels in a URL. A URL is a bearer capability in the one place that leaks
everywhere: proxy logs, referrers, a vetter's own crash report, the shell
history of whoever debugged the integration. Trading that boundary for a
capability nobody has yet asked for by name is the trade this project's stop rule
exists to refuse.

The push model also keeps a property the pull model gives away. Today the gateway
decides exactly which bytes leave, per vetter, per run, and can say so
afterwards. With a handle, the vetter decides what it reads and when, and the
ledger records that a fetch was authorized rather than what was seen.

### What would change the answer

One thing, stated so it can be recognised rather than argued about later: **a
real sandbox-detonation vetter, named, that needs the executable tree.** That is
the case the push contract cannot be stretched to cover, and the only one on the
horizon. Volume is not that case; a bigger cap answers volume. Convenience is
not that case either.

### The shape it would take, if it is built

Written down now so the decision is not re-litigated from scratch, and so nobody
reaches for the obvious-but-wrong version of it:

- **Not a URL into the quarantine repo.** A one-shot fetch of a bundle artifact
  the gateway has *already materialised* for that chain run — the same selection
  decision the push model makes, expressed as an archive instead of a JSON body.
  The quarantine repo stays unserved; what is served is a derived artifact with
  a known manifest.
- **A capability bound to `(snapshot, vetter, chain run)`**, single-use and
  minutes-lived, invalidated when the run ends. Not a signed URL over a bucket
  with an expiry and nothing else.
- **On `/api/**`, never the facade.** The facade serves approved content to
  clients; this is neither.
- **Recorded as a fetch, with the manifest digest**, so "what did this vetter
  see" is answerable from the ledger, which is the property the push model has
  for free and a handle otherwise loses.
- **Fail-closed on every branch**, exactly as ADR 0009 requires: a capability
  that expired, a fetch that never happened, a manifest that does not match, are
  each an error verdict that blocks.

## Alternatives considered

- **Build the signed URL now, since the shape is known.** Rejected. Knowing the
  shape is what makes it cheap to build *later*; building it now adds a serving
  path for unapproved content, a new credential kind, a sweep for expiry and a
  configuration surface, all for no vetter that exists.
- **Raise `max-request-bytes` and declare the problem solved.** Rejected as a
  *decision*, though it is the right operational answer to volume. It does
  nothing for binaries or tree shape, and saying "solved" would hide the one
  real gap.
- **Ship binaries inline, Base64 in the bundle.** Rejected: it multiplies the
  payload for content most vetters cannot use, and still does not give a sandbox
  a tree to unpack. If binary reach is the goal, the artifact fetch above is the
  honest design.
- **Let the external vetter clone the quarantine repo with a scoped PAT.**
  Rejected outright. That is the quarantine repo being served, with a
  long-lived credential, which is the boundary this ADR exists to hold.

## Consequences

- An LLM reviewer, a text scanner and a corporate policy engine are all fully
  serviceable today; nothing changes for them.
- A sandbox detonator cannot be built as an external vetter. That is a real
  capability gap, recorded here rather than discovered by someone halfway
  through building one. The
  [external-vetter guide](../manual/guides/adding-an-external-vetter.md) says so
  at the point where someone would hit it.
- Revisiting is cheap: the shape above is the design, and it starts when a named
  detonation vetter does.

## Decisions to confirm with the owner

1. **Is a sandbox detonator on the roadmap in a form that needs the executable
   tree?** If it is, this ADR's trigger has already fired and the artifact fetch
   should be proposed rather than parked.

Extends [ADR 0009 — The external vetting connector contract](0009-external-vetting-connector-contract.md),
whose "send a fetch handle instead of content" alternative this ADR answers in
full. Supersedes nothing.
