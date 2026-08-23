# Evidence: hosted-marketplaces

One final fresh run of every gate after the last code edit, against a clean
tree at `7560d133c9785af3ff3be70a78717a243361b4f2`. Edits after it are this
report, the tasks checklist, and the archive move — no source changes.

## Discipline notes (old-coder Tier 3 — this change adds a git write path)

- **Spec approval: not obtained (autonomous run).** The owner's brief was to
  work through issue #31. The committed OpenSpec change — `proposal.md`,
  `design.md`, `specs/`, `tasks.md` — plus **ADR 0007** are the spec the
  implementation was held to, offered for review after the fact rather than
  before. Confidence is claimed correspondingly lower.
- **Scope reduced deliberately, and said so.** Issue #31 asks for a
  "configurable fast-path for trusted internal publishers". It is **not**
  implemented. ADR 0006 already parked auto-approval until the
  delegated-approval question is decided deliberately; deciding it inside a
  plumbing change, while `four-eyes-approval` is in flight *tightening* the
  same gate, would decide it by accident. This is the one part of the issue
  left undone, and it is a decision for the owner rather than a gap.
- **RED observed for one of the four test classes.** `HostedMarketplaceTests`
  was written against a `register(...)` stub that threw, and all four cases
  failed on that behaviour rather than on compilation before any
  implementation existed. `HostedPushTests`, `HostedLifecycleTests` and the
  later additions were written after their implementation. The mutation pass
  below is the compensating evidence for those, not a substitute: it shows each
  can fail for the reason it claims to check.
- **The suite's own guard rails fired, and were honoured rather than loosened.**
  `AuthTests` asserts a closed enumeration of the token view's fields, with a
  comment saying any new field must be added deliberately — `pushScopes` was
  added there as a deliberate decision (a grant, like the others, never a
  secret). `RoleEnforcementTests`' route-table equality assertion needed no
  change: this change adds no `/api/**` route.
- **No existing SVC test was weakened.** `EstateReconciliationTests` and
  `AuthTests` were edited mechanically only (a widened record constructor, one
  added field name); no assertion changed meaning.

## Failure model coverage

`design.md` lists twelve failure modes. F1–F9 and F11–F12 each have a test
named in `tasks.md`. F10 (the scheduled sweep reaching a hosted marketplace's
absent upstream) is covered three ways — a CHECK constraint, the sweep's
existing `sync_mode = 'scheduled'` filter, and a refusal in
`SyncService.changeMode` asserted by `HostedMarketplaceTests`.

## Mutation pass

Fourteen mutants against the write path, applied one at a time and restored via
`git checkout --` (verified by `git diff --name-only` after each; `grep -rn
MUTANT src/` clean before this report). The runner fails closed: an anchor that
does not match exactly once, a mutant whose outcome differs from its declared
expectation, or a dirty tree after restore is a hard failure.

| # | Mutant | Outcome |
| --- | --- | --- |
| 1 | The publish resolver checks no push scope | KILLED |
| 2 | Push scope falls back to the fetch scope | KILLED |
| 3 | A push scope may name an upstream marketplace | KILLED |
| 5 | Absent push scopes mean *every* marketplace, like fetch scopes | KILLED |
| 6 | Any ref may be published, not just the lineage | KILLED |
| 7a | Deletion allowed by the `ReceivePack` | SURVIVED — **as declared**: the hook is the other half of the pair |
| 7b | Deletion allowed by the hook | SURVIVED — **as declared**: `setAllowDeletes(false)` is the other half |
| 7c | **Both** deletion guards removed | KILLED |
| 8 | `append-only` does not refuse a rewrite | KILLED |
| 9 | A permitted rewrite is not recorded on the ledger | KILLED |
| 10 | A push does not ingest | KILLED |
| 11 | A hosted registration accepts an upstream url too | KILLED |
| 12 | An upstream marketplace skips the scheme allowlist | KILLED |
| 13 | A hosted marketplace is ingested from its (null) upstream url | KILLED |

**Negative control.** A fifteenth "mutant" changing only a javadoc comment
**SURVIVED**, as required — the runner distinguishes killed from unkilled
rather than printing KILLED unconditionally.

### What the first mutation run actually found

The pass did not come out clean, and the failures were worth having:

1. **A real test gap.** The mutant removing the hosted-origin filter from the
   publish resolver survived, because the test pushed to an upstream
   marketplace that had no origin repository on disk — so the lookup answered
   not-found either way and the filter was never the reason. Fixed by creating
   the origin directory first, which is the only arrangement that tells the two
   apart (commit `adcfbca`).
2. **Two guards unreachable behind an invariant held elsewhere.** The
   name-pattern check and the resolver's origin filter both turned out to be
   defence in depth behind `TokenService.validatePushScopes`, which will not
   issue a push scope naming anything but a registered hosted marketplace. That
   invariant was *assumed and untested*. It is now asserted directly — an
   upstream name, the catalog name and an unknown name are each refused at
   issue time — and mutant 3 targets that guard, where it is killed. The
   redundant checks are kept, with comments saying they are redundant and why.
3. **A latent bug.** `push_policy` is `NOT NULL` with a `DEFAULT`, and a column
   default applies to an *omitted* value, not to an explicit null — so
   `register(..., pushPolicy=null)` failed at the database. The repository now
   coalesces. Production never hit this (the registration service always
   resolves a policy first), but the estate path and any future caller would
   have.

### Deletion is guarded twice on purpose

7a and 7b are the only mutants declared to survive, and the declaration is the
claim: each deletion guard holds on its own, so neither is load-bearing alone,
and 7c proves the pair is load-bearing collectively. A mutation runner that
scored 7a/7b as failures would be demanding that defence in depth be removed.

## Adversarial pass

All covered by tests in `HostedPushTests`:

- A fetch-scoped token, a wildcard-fetch token (`scopes IS NULL`, which grants
  *every* marketplace for fetch) and a token predating push scopes each attempt
  a real `git push` — all refused.
- A push-scoped token attempts a *different* marketplace, a name that escapes
  the storage layout (`..`, `../published/{name}`), and an upstream marketplace
  that has an origin directory on disk — all answer as not-found.
- The same token that publishes through `/publish` attempts `/git` — refused,
  because the facade has no receive-pack to construct.
- A second branch, a tag, and a ref deletion — all refused.
- A force-push under `append-only` — refused, with the origin tip asserted
  unchanged afterwards (a refusal that left the tip moved would be worse than
  no refusal).
- A force-push under `allow-rewrite` — accepted, with both tips asserted
  present on the append-only ledger.

## Known limits (declared, not covered)

- **No auto-approval fast path** — deliberately out of scope, see above.
- **No portal UI.** The register dialog does not yet offer the origin and the
  marketplace page does not show the publish URL. The API is the management
  surface, as it already is for role grants; the generated types are in place
  so the UI is a self-contained follow-up.
- **The origin repository inherits the facade's protocol threat surface** —
  malformed objects, oversized packs, resource exhaustion — since it is the
  same JGit implementation. No pack-size or object-count limit is imposed by
  this change on either endpoint; hardening one would harden both, and that is
  a separate piece of work.
- **Concurrency is JGit's ref lock plus the existing per-marketplace ingestion
  lock**, not a stress test. Two simultaneous pushes resolve as one
  fast-forward and one stale non-fast-forward, which is git's own semantics.
- **Ingestion after a push is best-effort**: the objects are safely in the
  origin repository, which is what git promised the publisher, and an ingestion
  failure is logged and put on the ledger rather than turned into a push
  failure the publisher cannot act on. A failed ingest leaves content pushed
  and un-ingested, recoverable through the ordinary ingest endpoint. There is
  no test for the failure path itself.
- **`allow-rewrite` weakens lineage provenance** for marketplaces that choose
  it. Approved snapshots keep their content (pinned by SHA); what a rewrite
  destroys is the history, and after one the ledger is the only record of it.

## Gates

### `./mvnw clean verify`

```
[INFO] Tests run: 170, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:15 min
```

### `(cd src/main/frontend && pnpm test:stories)`

```
 Test Files  3 passed (3)
      Tests  6 passed (6)
```

### `(cd src/main/frontend && pnpm e2e)`

```
  12 passed (27.7s)
```

### `reqstool status local -p docs/reqstool`

```
INCOMPLETE (0)
100/100 complete · 0 incomplete · PASS
```

### `openspec validate --all --strict`

```
Totals: 26 passed, 0 failed (26 items)
```

### `mkdocs build --strict`

```
INFO    -  Documentation built in 0.84 seconds
```

## Commit

Final implementation commit: `7560d133c9785af3ff3be70a78717a243361b4f2`
(every gate above ran against this tree; the archive commit follows).
