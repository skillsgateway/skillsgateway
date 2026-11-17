# ADR 0024 — The gateway does not host marketplaces

*Accepted, 2026-11-17. Supersedes
[ADR 0007](0007-first-party-hosting-and-the-publish-endpoint.md) in full.*

## Context

ADR 0007 added a write path: a marketplace registered with `origin: "hosted"`
and no clone URL, a push endpoint at `/publish/{name}` behind a token scope no
token holds by default, a third bare repository at `{data-dir}/hosted/{name}.git`
that ingestion fetches from, and a rewrite policy per marketplace. The reason
was real: an organisation that authors its own skills had to stand up a forge
repository purely so the gateway had something to pull from.

The feature has not been adopted as a way of working, and the cost of keeping it
is not the code, it is the position the code puts the product in. The gateway
exists to be the one place content is vetted and approved before an agent reads
it. Hosting makes it also a place content is *authored and stored*, which is a
different product with a different owner.

## Decision

**The gateway governs content; it does not host it.** A marketplace is always
fetched from an upstream. There is no hosted origin, no publish endpoint, no push
scope and no third repository role. ADR 0007 is superseded in full.

An organisation that authors its own skills keeps them on a forge — GitHub,
Gitea, GitLab — and registers that repository as an upstream. A private
repository works already, through an upstream credential. Everything the gateway
adds (quarantine, vetting, the approval gate, the SHA-pinned snapshots, the
ledger) applies to it exactly as it applied to a pushed commit.

## Why

- **A forge does hosting better.** Review, branch protection, required checks,
  signed commits, CODEOWNERS, backup, a permission model people already
  administer and a history people already read. The gateway offered a lineage
  with a single ref and a rewrite flag.
- **A write credential on a security product.** The push scope was the only
  write path the gateway had, and the facade's strongest sentence — receive-pack
  is disabled by construction — had to be narrowed to make room for it. The
  credential was granted by an approver, held as a bearer secret, and checked on
  a second servlet with its own filter chain, which is surface to harden and to
  keep honest. No credential, no surface.
- **A second system of record.** A hosted marketplace's repository is the only
  copy of its source. Quarantine and the served repository are rebuildable from
  an upstream; this one is not. The gateway would own its backup, its restore,
  its retention and the answer to "who deleted it", none of which anything else
  it stores demands.
- **The stop rule.** AGENTS.md asks, before new scope, for an existing surface
  that can be narrowed or removed. This is that surface: two enum types, a
  constraint pair, a token column, a storage role on two backends, five ledger
  events, an auto-approval exemption, a servlet, a filter chain and a guide, for
  a capability a forge provides.

## Consequences

- **A breaking change.** `origin`, `pushPolicy`, `publishPath`, `pushScopes` and
  the `hosted` value of the auto-approval `allowedBy` enum leave the API contract;
  the declarative estate loses `origin` and `push-policy`; `/publish/**` stops
  being routed. A request that still asks for the removed behaviour is refused in
  words rather than ignored. This is declared under the compatibility page's
  rules and ships as the project's versioning dictates.
- **The upgrade deletes the gateway's record of every hosted marketplace, and
  nothing else.** The migration purges each hosted marketplace with its snapshots,
  approvals, vetting history and the ledger entries recorded under its identity;
  that is a decision for a beta, taken so the schema can be clean. It does not
  touch the repositories: the bare repository of each remains in the data
  directory (or the object store), and is the only copy of that publisher's
  source. Those marketplaces stop being served at the upgrade. Administrators are
  told in the portal, with a temporary notice that lists each remaining
  repository and disappears when the folders do. Tokens keep their fetch scopes;
  only the push scope, which nothing can use, goes.
- **Served content is interrupted until the forge copy is approved.** An
  operator copies each repository to a forge, registers it under the old name
  (free after the purge) and approves its first snapshot; consumers get nothing
  from that name until then. Registering the forge copy under a temporary name
  before upgrading avoids the gap.
- **ADR 0007's invariants survive.** Served content is still an approved,
  SHA-pinned snapshot; the quarantine repository is still written only by
  ingestion; only `ApprovalService` publishes to the served repository. The
  statement "the facade accepts no push" becomes the stronger "the gateway accepts
  no push", true everywhere again.
- **Auto-approval of internal marketplaces** (ADR 0023) loses one input: a hosted
  marketplace was internal by construction. Internal now means under an operator
  prefix, nothing else.
- **The audit ledger loses only the hosted marketplaces' own entries.**
  `marketplace-pushed`, `marketplace-lineage-rewritten`,
  `marketplace-push-ingest-failed` and `marketplace-push-scopes-removed` are never
  written again; the entries recorded under a purged marketplace's identity are
  deleted by the upgrade, and every other entry stays, including those of an
  upstream that reused a removed name. An operator who needs the evidence exports
  the ledger before upgrading.

## Alternatives considered

- **Keep hosting, document it as unsupported.** Leaves the credential, the
  surface and the second system of record in place and adds a sentence.
- **Deprecate for a release, then remove.** A deprecation of a write path is a
  statement that the gateway still holds credentials for it, and the project is
  in beta.
- **Convert hosted marketplaces automatically.** The gateway cannot create a
  forge repository or credentials for it, and it must not guess a URL.
- **Refuse the upgrade while a hosted marketplace is live.** Loses nothing, but
  an upgrade that cannot proceed until an operator has done a procedure under the
  old version is an outage of its own; the owner chose the beta's clean break and
  a loud notice instead.

## Reopening this

If an organisation with no forge turns up, a self-hosted forge such as Gitea is
a small deployment of its own. Hosting in the gateway would need a new ADR that
answers the three costs above, not a flag.
