# Design: read-only-forge-mirror

## Context

- `ApprovalService.doApprove` is the only publication path: it decides the row,
  calls `GitStorage.publish`, rebuilds the catalog and returns. A publication
  that fails puts the row back (GW_APPROVAL_0012), so the method already has a
  well-defined "the content is now served" point.
- `RevetService.quarantine` is the only revocation path. It revokes the row
  first, conditionally on the snapshot still being approved, then calls
  `GitStorage.unpublish`, and — this matters below — **logs and records an
  unpublish that failed rather than failing the revocation**.
- `GitFacadeConfiguration` advertises exactly `refs/heads/main` and
  `refs/snapshots/*` (plus `HEAD`), through a private `RefFilter`.
- `MarketplaceRegistrationService.requireAllowlistedScheme` is the existing URL
  policy: parse, take the scheme, refuse anything not in
  `skills-gateway.allowed-url-schemes`, fail closed on unparseable and
  scheme-less.
- `WebhookService` is the existing outbound integration and is enqueue-only by
  design: "an administrative action never blocks on, or fails because of, a
  receiver". `SyncConfig` is the existing precedent for a single daemon thread.

## Goals / Non-Goals

**Goals**

- The mirror cannot affect what the facade serves, or whether an approval or a
  revocation succeeds — as a property of the wiring, not of care.
- Only approved, currently served content is ever pushed.
- Revocation reaches the mirror, and a mirror that cannot be reached during a
  revocation does not hold the revocation up.
- Divergence is observable, because every other property here is stated in the
  negative and the cost of that safety is drift accumulating quietly.

**Non-Goals**

- No forge API of any kind: no repository creation, no description or README
  writing, no labelling. Plain git push over the configured transport.
- No second mirrored marketplace, no estate object, no durable queue, no
  scheduled reconciliation sweep. Named in the proposal's out-of-scope list.

## Decisions

### 1. Asynchronous, and the reason is the requirement rather than latency

A synchronous push would make an approval's success depend on a forge being up.
That is not a performance objection, it is GW_FACADE_0021 — *The mirror is never an
enforcement path* — and the failure mode it prevents is specific: an
organisation that cannot approve content because a forge is down will turn the
approval gate off long before it turns the mirror off.

So publication and revocation raise a Spring `ServedContentChangedEvent`, and
`MirrorPublicationListener` queues work on a single daemon thread. Two things
make that safe rather than merely conventional:

- The event is raised **after** the transition it describes has landed. There is
  nothing left for a listener to fail.
- Spring's default multicaster runs listeners on the publishing thread and lets
  their exceptions escape into the publisher, so the listener catches everything
  and the queueing call is non-blocking and throws nothing. Two latches on the
  same door, because one of them is a convention of a framework we do not own.

The alternative considered was a direct call from `ApprovalService` to the
mirror, guarded by a `try/catch`. It works, and it was rejected because the guard
is exactly the kind of thing a later refactor removes without noticing: with an
event, approval has no reference to the mirror package at all, and reintroducing
the dependency is a visible act.

### 2. The unit of work is a reconciliation, not a delta

A task does not mean "push snapshot X" or "delete snapshot Y". It means: make
the mirror hold what published storage holds, in the served namespaces, right
now. Concretely — read `refs/heads/main` and `refs/snapshots/*` from published
storage, ls-remote the mirror, push every reference that differs, delete every
reference in those namespaces that the served set no longer has.

This is the load-bearing decision of the change, and it buys four things at once:

- **Revocation propagates with no second code path** (GW_FACADE_0022). A revoked
  snapshot is a reference the served set no longer has, so the next
  reconciliation deletes it. There is no "unmirror" operation to get wrong.
- **It is idempotent and self-healing.** A dropped, duplicated or reordered task
  cannot corrupt the mirror. This is what makes an in-memory queue an honest
  choice rather than a shortcut: losing the queue to a restart costs freshness
  until the next approval or revocation, and the drift report says so meanwhile.
- **It repairs drift the gateway did not cause.** Somebody pushing to the mirror
  by hand is corrected by the next reconciliation, because the comparison is
  against the mirror's actual references rather than against push history.
- **It cannot mirror anything but approved content.** The only repository it
  reads is `published`. There is no code path from quarantine, or from a hosted
  marketplace's origin, to a push — not a check that could be forgotten, an
  absence of a call site.

Deletions are computed over the served namespaces only, because both sides are
read through `GitStorage.isServedRef`. A forge repository's own branches, tags
and `HEAD` are outside them, so the gateway neither reports them as drift nor
erases them.

### 3. What happens when the push fails during a revocation

**The gateway's own state is authoritative; the mirror is flagged as drifted.**
The revocation completes, the facade stops serving, and the failed mirror update
becomes a `mirror-push-failed` ledger entry and a `failed` outcome on the drift
report.

The two alternatives are worse in the same direction:

- *Block the revocation until the mirror confirms.* An unreachable forge then
  keeps known-bad content being served **through the facade** — the surface that
  actually matters — which inverts the control. Retroactive quarantine exists
  precisely for content that turned out to be malicious; making it wait on a
  third party is the one thing it must not do.
- *Reverse the revocation on push failure.* Same outcome, arrived at more
  surprisingly, and it would mean an external system can un-revoke content.

This is also the honest description of what a forge copy can promise. A mirror
can be raced by caches, forks and clones the moment it exists, so it was never
what makes a revocation effective — the facade failing the next fetch is. The
requirement therefore protects the facade's behaviour and makes the mirror's lag
visible, rather than pretending the mirror can be authoritative.

The same posture already exists one layer down, which is why this is consistent
rather than novel: `RevetService` records `snapshot-unpublish-failed` and carries
on rather than un-revoking when `GitStorage.unpublish` fails. The mirror event is
raised in that case too — the reconciliation is over what is served *now*, so it
repairs a mirror that a failed unpublish would otherwise have left holding the
revoked snapshot.

### 4. Retries end, and ending is a designed state

Three attempts by default, with a delay between them, then stop. Giving up is not
a lost update: the next approval or revocation of that marketplace pushes the
whole served set again. What a failure costs is freshness until then, and the
drift report is what makes that cost visible instead of silent.

### 5. An invalid configuration fails startup; an unreachable mirror does not

`MirrorTarget.from` validates at startup and throws when the mirror is enabled
with no marketplace, no URL, a scheme outside the allowlist, or a URL embedding a
credential. The gateway then refuses to start.

This is not a violation of "the mirror never affects serving": **nothing is
contacted**. What is validated is a configuration string, offline and
deterministically. An operator who asked for a mirror and mistyped its scheme
would otherwise get no mirror and no signal, and the project already refuses to
start on comparable configuration mistakes (`DevInsecureAuthGuard`, the role
bootstrap guard, estate reconciliation). A forge that is down, refuses the
credential, or has been changed underneath the gateway is the other case entirely
and never affects anything.

### 6. Credentials

`skills-gateway.mirror.username` and `.token`, supplied as configuration or
environment, never committed. Three things keep them out of the record the
gateway exists to produce: the URL is refused if it embeds userinfo, so every
place that prints a URL is safe; `MirrorTarget` is a class rather than a record
so no generated `toString` can print the token; and the `Mirror` properties
record overrides `toString` for the same reason.

### 7. Operator configuration, not an estate object

CLAUDE.md's continuous obligation asks whether new API-managed runtime state must
extend `skills-gateway.estate.*`. **It must not, and this is deliberate.** The
mirror is not runtime state the API manages: no endpoint creates, edits or
deletes it, and the drift report is a read. It is one deployment-level outbound
integration carrying a standing write credential to another system — the same
category as the storage backend or the OIDC issuer, both of which are properties
rather than estate objects. Making it declarative would mean either putting a
forge credential in the estate document or splitting the object across two
configuration mechanisms.

That answer would change if the mirror became per-marketplace, which is where
issue #59 points next: a marketplace's mirror repository is then a property of
the marketplace, and `estate.marketplaces` is where it would belong, with the
credential still a deployment-level property. Recorded here so the next increment
inherits the question rather than the answer.

### 8. `GitStorage.isServedRef`

The facade held the served namespaces in two private constants. The mirror needs
the same definition, and a second spelling of "what is served" would be a mirror
that drifts by construction — so the predicate moved to `GitStorage`, beside
`STAGING_REF_PREFIX`, and the facade now calls it. `HEAD` stays out of it: the
facade keeps advertising `HEAD` so a clone knows which branch to check out, which
is a property of an advertisement rather than of the served set.

## Risks / Trade-offs

- **The queue is in memory.** A restart with work queued leaves the mirror stale
  until the next approval or revocation. Accepted because reconciliation is
  idempotent and the drift report makes the staleness visible; a durable queue is
  named as out of scope rather than forgotten.
- **Drift the gateway did not cause is not corrected until something else
  happens.** There is no scheduled sweep in this increment. The report is the
  mitigation, and the operator action — approve or revoke anything, or restart —
  is available.
- **One marketplace.** The configuration is deliberately not a list, so that
  growing it is a visible change rather than a value.
- **`file://` in tests.** The suites push to a bare repository on disk, which
  requires `file` on the scheme allowlist — already the case in the shared test
  context, and asserted as *refused* under the default allowlist in
  `MirrorUrlPolicyTests`.

## Verification

`ForgeMirrorTests` and `ForgeMirrorFailureTests` run against real JGit transports
(`Transport.push`, `LsRemoteCommand`) over `file://`, so no test reaches the
network and none of them mocks the thing under test. The failure suite's
assertions are deliberately about the approval, the revocation and a real `git
clone` through the facade — if the mirror were an enforcement path, that is where
it would show.
