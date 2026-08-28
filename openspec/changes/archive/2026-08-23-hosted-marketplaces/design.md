# Design: hosted-marketplaces

## Context

The pipeline below ingestion is already source-agnostic. `VettingService.run`
opens `new QuarantineSnapshot(id, marketplace, sha, maxFileBytes,
storage.quarantine(marketplace))` and never sees a URL; `ApprovalService`
publishes by fetching `refs/snapshots/<sha>` out of quarantine into the
published repository; `PolicyGate` is handed `marketplace.name()`, not its
origin. The only upstream-shaped thing downstream of ingestion is
`Provenance.upstreamUrl`.

Ingestion itself has exactly one upstream dependency:

```java
ObjectId sha = fetchUpstreamHead(repo, marketplace.url());
```

Everything after that line — pinning `refs/snapshots/<sha>`, the
`findByMarketplaceAndSha` dedupe, `validateManifest`, the `held`/`rejected` row,
`vettingService.vet` — is about a commit that is already in quarantine and does
not care how it arrived.

So first-party hosting is a question of *where the bytes come from*, and of
building the one thing the product deliberately does not have: a git write path.
`GitFacadeConfiguration.gitServlet()` calls `setReceivePackFactory(null)` and
six documentation pages say pushes are impossible — the trust-boundary model,
the facade reference, the compatibility matrix, the glossary, the consuming
guide and the lifecycle concept. That is why this change carries ADR 0007.

## Goals / Non-Goals

**Goals**

- Register a marketplace with no upstream, push its content to the gateway over
  ordinary git, and have it traverse quarantine, vetting and approval exactly
  as fetched content does.
- Leave the consumer facade byte-for-byte read-only.
- Keep "only `IngestionService` writes quarantine" true.
- Make a push credential a different thing from a fetch credential, empty by
  default on every token that exists today.
- Give a hosted marketplace a stated immutability policy, because a publisher
  who can rewrite history can change what a reviewer approved.

**Non-Goals**

- **No auto-approval.** Issue #31's "fast-path for trusted internal publishers"
  is out of scope by ADR 0006, which parked auto-approval until the
  delegated-approval question is decided deliberately. See `proposal.md`.
- **No multi-branch or tag publication.** One lineage per marketplace, the same
  guarantee GW_0017 makes for upstreams.
- **No web upload, no admin CLI push.** Git is the interface; the CLI is a
  later convenience over the same endpoint.
- **No conversion between origins.** A marketplace is hosted or upstream at
  registration and stays that way — changing it would swap the supply chain
  under snapshots that were already approved, which is the same reasoning that
  makes an upstream URL immutable in the estate reconciler.
- **No mirroring out to a forge.** That is issue #59.

## Decisions

1. **A hosted marketplace gets a third repository, not a writable quarantine.**
   `{data-dir}/hosted/{name}.git` is the publisher's source of record. Ingestion
   fetches from its filesystem path into quarantine with the same JGit fetch it
   uses for a remote URL, so the incoming ref, the snapshot pin and everything
   downstream are literally the same code.
   *Alternative rejected:* pushing straight into the quarantine repository and
   triggering ingestion from a `PostReceiveHook` on it. It saves a copy of the
   objects and costs the invariant that quarantine has exactly one writer.
   Quarantine also holds the `refs/snapshots/<sha>` pins that vetting and
   approval address content by; letting an external credential write into that
   ref namespace is a far larger surface than letting it write one branch of a
   repository nothing else reads.

2. **A separate endpoint, not a mode on the existing one.** `/publish/**` is its
   own `ServletRegistrationBean<GitServlet>` with its own resolver and its own
   `SecurityFilterChain`, ordered beside `gitChain`. `GitFacadeConfiguration` is
   not edited at all, so `setReceivePackFactory(null)` and GW_0006/GW_0007 stay
   exactly as they are and no reviewer has to reason about whether a mode flag
   could flip on the consumer path.

3. **`origin` is a column, not an overloaded `sync_mode`.** Where content comes
   from and how often it is re-checked are different questions;
   `sync_mode`'s CHECK constraint enumerates three refresh strategies, none of
   which describe "the gateway holds it". A hosted marketplace is pinned to
   `on-demand` by a CHECK constraint and `PUT /marketplaces/{name}/sync` refuses
   it — its ingestion trigger is the push.

4. **Push scopes are a separate column with no wildcard.**
   `access_tokens.push_scopes` is a comma-delimited marketplace list like
   `scopes`, but where `scopes IS NULL` means *every* marketplace (GW_0064's
   compatibility rule for pre-scoping tokens), `push_scopes IS NULL` means
   **none**. Every token that exists today therefore cannot push, and no token
   can ever be granted push to everything by omission. A push to a marketplace
   outside the scope answers exactly as it does for one that does not exist,
   following GW_0064's precedent.

5. **`/publish` serves upload-pack as well as receive-pack, to the same scope.**
   A publisher needs to clone their own source of record onto a new machine or
   into CI. The repository being read is the one that credential can already
   write, so upload-pack adds no reach; it is gated by the same push scope, and
   it is not the quarantine repository, so no unapproved *snapshot* content
   becomes fetchable through it. GW_0007 speaks about the facade and is
   untouched.

6. **Lineage and immutability are enforced in a `PreReceiveHook`, before any
   object is referenced.** A push may update only `refs/heads/main`; a ref
   delete is always refused; a non-fast-forward is refused under the default
   `append-only` policy. `allow-rewrite` exists because some publishers really
   do amend, and it is a per-marketplace decision that lands on the ledger with
   the old and new tip, so "what was approved has since been rewritten" is
   answerable.

7. **Ingestion happens after the push completes, not inside the hook.** A
   `PostReceiveHook` records the ledger entry and hands off; vetting a snapshot
   inside the receive-pack transaction would make `git push` block on the
   connector chain and make its timeout the publisher's timeout. The push
   succeeds when the objects are safely in the origin repository — which is the
   promise git makes — and the snapshot appears `held` immediately after.

8. **`Provenance.upstreamUrl` becomes null for hosted marketplaces**, and the
   provenance view gains the origin so a consumer can tell "no upstream" from
   "upstream not recorded". Renaming the field would break the generated portal
   types and every archived spec that names it, for a cosmetic gain.

## Failure model (Tier 3)

| # | Failure mode | Layer that catches it |
| --- | --- | --- |
| F1 | A push reaches a *published* repository and serves content nobody approved | `/git/**` keeps `setReceivePackFactory(null)` and is not edited; a test pushes to `/git/{name}` and asserts refusal |
| F2 | A token with only fetch scopes can push | Push authority derives from `push_scopes` alone; negative tests for a fetch-scoped token, a wildcard-fetch (`scopes IS NULL`) token, and an unscoped legacy token |
| F3 | A push-scoped token pushes to another marketplace | Scope checked in the resolver before the repository is opened; answers not-found, as GW_0064 requires |
| F4 | A publisher rewrites history under a snapshot that was already approved | `append-only` `PreReceiveHook` refuses non-fast-forward; the `allow-rewrite` case is ledger-recorded with both tips |
| F5 | A push creates a second lineage (another branch or a tag) and a later ingest picks the wrong one | The hook refuses every ref but `refs/heads/main`; test pushes a branch and a tag |
| F6 | A ref delete empties the origin and the next ingest fails or, worse, ingests nothing | Deletes always refused, under both policies |
| F7 | Pushed content skips vetting because ingestion took a different path | The pushed path joins the existing one at the snapshot pin; a push of the tainted fixture must land `held` with findings, and a manifest violation must land `rejected` |
| F8 | Hosted content is served before approval | Same approval gate; test asserts the facade 404s the marketplace until approve |
| F9 | A hosted marketplace with a null URL NPEs somewhere that assumed one | Null-safe `EstateReconciler` comparison, `ForgeMetadataService` skip, provenance; a hosted marketplace is driven through register → push → ingest → vet → approve → fetch → revoke end to end |
| F10 | The scheduled sync sweep tries to fetch a hosted marketplace's absent upstream | Hosted is pinned to `on-demand` by CHECK; the sweep query already filters `sync_mode = 'scheduled'`; `changeMode` refuses hosted |
| F11 | A marketplace name in the push URL escapes the storage directory | The same `^[a-z0-9][a-z0-9_-]*$` pattern the facade resolver uses, applied before any path is built; traversal attempts tested |
| F12 | Two publishers push concurrently and the second's content is silently lost | Receive-pack's own ref lock rejects the stale update as non-fast-forward; ingestion keeps its per-marketplace `ReentrantLock` |

## Migration

`CLAUDE.md` says schema changes fold into `V1__init.sql` until the owner says
otherwise, because Testcontainers recreate the schema every run. Four columns
and two constraints:

```sql
url TEXT,                                        -- was NOT NULL
origin TEXT NOT NULL DEFAULT 'upstream' CHECK (origin IN ('upstream', 'hosted')),
push_policy TEXT NOT NULL DEFAULT 'append-only'
    CHECK (push_policy IN ('append-only', 'allow-rewrite')),
CHECK (origin = 'hosted' OR url IS NOT NULL),
CHECK (origin <> 'hosted' OR sync_mode = 'on-demand')
```

plus `access_tokens.push_scopes TEXT`.

## Risks

- **This is a write path into a product that advertises not having one.** The
  mitigation is that it writes somewhere nothing serves from, behind a
  credential nobody currently holds, and that the approval gate is the same
  gate. ADR 0007 states this so the next reader does not have to reconstruct it.
- **`allow-rewrite` genuinely weakens provenance** for the marketplaces that
  choose it. Snapshots are pinned by SHA and keep their content regardless, so
  what a rewrite can destroy is the *lineage*, not an approved snapshot. Stated
  in the guide rather than hidden behind a flag name.
- **Storage is now three repositories per hosted marketplace.** Objects are
  duplicated between the origin and quarantine. Accepted: it buys the
  single-writer property on quarantine, and the DFS-over-object-storage
  roadmap item (`architecture.md` §12) changes the arithmetic anyway.
