# Design — fix-audit-records-wanted-snapshot-ref

## What the code does today, established rather than assumed

The prior triage on #206 called this "one localized fix in
`AuditingPreUploadHook`". That is right about the location and wrong about one of
the consequences it attributes to it. Both halves are established here from the
code, because the fix's shape depends on which is true.

**The location.** `GitFacadeConfiguration` holds one constant,
`SERVED_REF = "refs/heads/main"`, used in three places:

| Site | Use | Correct today? |
| --- | --- | --- |
| `SERVED_REFS` ref filter | main is one of the two advertised namespaces | yes |
| `resolvePublished` `info-refs` entry | resolves `refs/heads/main` and records it | yes — an advertisement is about the tip |
| `AuditingPreUploadHook.onSendPack` | recorded verbatim for **every** want | **no** |

So the defect is one argument at one call site. There is no second site, and no
other reader of the ledger derives anything from `ref`.

**The consequence the triage got wrong.** The issue states that adoption
reporting mis-attributes a by-SHA fetch of a superseded snapshot to the current
tip. It does not, and the reason is that adoption never reads `ref`:

| Query | Groups / filters on | Reads `ref`? |
| --- | --- | --- |
| `FetchLogRepository.adoptionSince` | `GROUP BY marketplace, sha` | no |
| `FetchLogRepository.marketplaceAdoptionSince` | `GROUP BY marketplace` | no |
| `FetchLogRepository.latestFetchPerIdentity` | `DISTINCT ON (principal, marketplace)`, selects `sha` | no |
| `FetchLogRepository.fetchersOf` | `WHERE sha = :sha` | no |

`AdoptionService` then marks a SHA current by comparing it against the tip it
resolves from the published repository. The `sha` column holds `want.name()`,
which for a `refs/snapshots/<sha>` want *is* that snapshot's commit —
`IngestionService` pins `refs/snapshots/<sha>` at exactly `<sha>`, so the ref
name and the object id agree by construction. A fetch of a superseded snapshot
therefore already lands in its own per-SHA row with `current: false`.

That matters for scope: the fix is a correctness fix on the ledger's own
statement about itself, not a repair of a downstream report. Nothing that reads
the ledger changes, which is why no adoption requirement is added and no
adoption test changes.

## The resolution

`PreUploadHook.onSendPack` receives `Collection<? extends ObjectId>`. It does not
receive ref names, and JGit does not expose the client's `want-ref` lines to the
hook — `UploadPack`'s public surface offers `getAdvertisedRefs()`,
`getRefFilter()` and `getAdvertiseRefsHook()`, and nothing that names what the
client asked for. So the only available derivation is object id → advertised ref.

That derivation is sound because it is taken over the *same* set the facade put
on the wire: `UploadPack.getAdvertisedRefs()` returns the map after
`SERVED_REFS` has filtered it. The ledger therefore cannot name a ref the facade
did not advertise, which is a stronger property than resolving against the
repository would give.

Order of preference, and why:

1. **The want equals `refs/heads/main`'s advertised tip → `refs/heads/main`.**
2. **Otherwise, the want equals a `refs/snapshots/<sha>` tip → that ref.**
3. **Otherwise → no ref (`null`).**

`HEAD` is advertised too and points at main's tip; rule 1 subsumes it, so `HEAD`
is never recorded — the ledger names the branch a clone resolved, which is what
a reader of it can act on.

### Why the tip wins, and what that costs

While a snapshot is current, `refs/heads/main` and `refs/snapshots/<sha>` are the
same object id, so `git clone` and `git fetch <url> <sha>` send an identical want
list. No rule can separate them, so the choice is only about which name to
record.

The tip wins for two reasons. It keeps every row that is correct today correct —
an ordinary clone has always recorded `refs/heads/main` and continues to, so the
column does not change meaning mid-history for the overwhelmingly common event.
And it confines the new value to exactly the case that is wrong today: a want
that is *not* main's tip can only have come from a snapshot ref, which is the
superseded-snapshot fetch #206 is about.

The residual imprecision is stated in the documentation rather than left to be
rediscovered: **for content the marketplace is currently serving, the ledger
records the tip even if the client named the snapshot ref.** It is a limitation
of the protocol at this seam, not of the implementation, and it costs nothing
evidentially — the `sha` column pins the delivered content exactly, and the two
names denote the same commit.

The rejected alternative was "snapshot wins": it would flip the recorded ref for
every ordinary clone of a marketplace to `refs/snapshots/<sha>`, changing the
value on the common path to serve a case it still cannot actually distinguish.

### Why the no-match case records `null`

Under `RequestPolicy.ADVERTISED` — the default, and the facade sets no other —
every want is an advertised tip, so rule 3 is unreachable through the servlet.
It is written anyway, and tested, because the alternative is the bug: falling
back to a constant is how the current code states a ref nobody asked for. A
column whose whole purpose is to say what was asked for must be able to say
"not known" instead of guessing.

### Shape

The resolution is a package-private static function taking the advertised map and
one want, returning the ref name or `null`. That keeps the ambiguity rule and the
no-match fallback testable as pure logic — no git client, no container, no
`UploadPack` — while the hook stays three lines. Two behaviours also get
real-client coverage, because a pure-function test cannot prove the hook is
handed the filtered advertised set at the moment the pack is sent.

## Requirement

`GW_0154` (git-facade): the fetch ledger records, for each transferred want, the
advertised ref that want resolves to. `SVC_GW_0154` verifies the
superseded-snapshot case, the current-tip case, and the deterministic resolution
of the ambiguity.

`GW_0008` is not amended. It already requires the ref of every facade fetch to be
recorded; `GW_0154` states which ref that is when more than one is advertised.

## Ledger history

Nothing is backfilled. The ledger is append-only and the product issues no
`UPDATE` or `DELETE` against it; rewriting history to make old rows look accurate
would be a far worse defect than the one being fixed. Rows written before this
change keep saying `refs/heads/main` for a snapshot-ref fetch, and the audit
reference page says so.

## Open questions

None. The one the issue left open — whether adoption should attribute a by-SHA
fetch to the snapshot named — is answered above by the queries themselves: it
already does.
