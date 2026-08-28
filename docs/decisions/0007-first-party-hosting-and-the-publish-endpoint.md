# ADR 0007 — First-party hosting: a write path, deliberately somewhere else

*Accepted, 2026-08-23.*

## Context

Every marketplace the gateway governs has had to exist somewhere else first.
Registration takes a clone URL, ingestion fetches an upstream default branch,
and a registration without a URL is refused. An organisation that authors its
own skills has therefore had to stand up a forge repository purely so the
gateway has something to pull from — and then govern the same content in two
places, with two sets of permissions and two audit trails.

Issue [#31](https://github.com/skillsgateway/skillsgateway/issues/31) asks for
first-party hosting. The pipeline is not the obstacle: vetting, approval,
publication and the ledger address the quarantine repository by name and SHA
and know nothing about upstreams. The obstacle is that the one git write path a
gateway could offer is disabled by construction, and said to be, repeatedly:

> Receive-pack is disabled by construction — the servlet is configured with a
> null receive-pack factory, so no `ReceivePack` can be created at all.
> — `reference/git-facade.md`

The same claim appears in the trust-boundary model, the compatibility matrix,
the glossary, the consuming guide and the lifecycle concept. Adding a push
therefore cannot be a quiet implementation detail; it changes a statement the
product makes about itself.

## Decision

**The gateway accepts pushes — on a different endpoint, into a different
repository, under a credential nobody holds by default.**

Concretely:

1. **`/publish/**` is a separate servlet** with its own repository resolver,
   its own receive-pack factory and its own security filter chain.
   `GitFacadeConfiguration` is not edited: `/git/**` keeps
   `setReceivePackFactory(null)`, so the endpoint that serves approved content
   still cannot construct a `ReceivePack` at all.

2. **A push lands in a third repository, not in quarantine.**
   `{data-dir}/hosted/{name}.git` is the publisher's source of record.
   Ingestion fetches out of it into quarantine with the same JGit fetch it uses
   for a remote URL. Quarantine keeps the property that only `IngestionService`
   writes it, and the `refs/snapshots/<sha>` namespace that vetting and approval
   address content by stays out of reach of any external credential.

3. **Push authority is a separate token scope with no wildcard.** Where a fetch
   scope may be absent to mean *every marketplace* — the compatibility rule
   GW_0064 kept for tokens that predate scoping — an absent push scope means
   *none*. Every token that exists today can publish nothing, and no token can
   ever be granted publication to everything by omission.

4. **A publisher may move one lineage, forward.** Only `refs/heads/main` may be
   updated, no ref may be deleted, and history may not be rewritten unless the
   marketplace was registered saying it may — in which case both tips land on
   the append-only ledger.

5. **Nothing about the approval gate moves.** A pushed commit is quarantined,
   manifest-checked, vetted and held exactly as a fetched one, and is served
   only after somebody approves it.

## Consequences

- The architecture's invariants survive unchanged. Served content is still an
  approved, SHA-pinned snapshot; the quarantine repository is still never
  exposed; only `ApprovalService` still publishes. What is new is a *fourth*
  place content can come from before quarantine, which is precisely where the
  gateway already assumes nothing is trustworthy.
- The documentation's "pushes are impossible" claim becomes "the **facade**
  accepts no push", which stays true and is now the more precise statement. The
  six pages that made the broader claim are corrected rather than deleted.
- Storage is three repositories per hosted marketplace, with objects duplicated
  between the origin and quarantine. This is the price of the single-writer
  property on quarantine, and the DFS-over-object-storage roadmap item
  (`architecture.md` §12) changes the arithmetic anyway.
- **Auto-approval remains parked.** Issue #31 also asks for a "configurable
  fast-path for trusted internal publishers".
  [ADR 0006](0006-embedded-cel-for-policy-rules.md) already decided that
  question — auto-approval "contradicts the product's first principle (nothing
  is served that a person did not approve) and stays parked until the
  delegated-approval question is decided deliberately, together with the
  risk-tier machinery". Deciding it inside a plumbing change, while the
  four-eyes rule is in flight *tightening* the same gate, would decide it by
  accident. It needs its own proposal and its own ADR.
- `allow-rewrite` genuinely weakens lineage provenance for the marketplaces
  that choose it. Snapshots are pinned by SHA and keep their content regardless,
  so a rewrite destroys the history, not an approved snapshot — but the guide
  says so plainly rather than hiding it behind a flag name.
- An origin repository is a git protocol endpoint exposed to a credential
  holder, so it inherits the same threat surface as the facade's upload-pack:
  malformed objects, oversized packs, resource exhaustion. It runs on the same
  JGit implementation behind the same authentication, and hardening either
  hardens both.
