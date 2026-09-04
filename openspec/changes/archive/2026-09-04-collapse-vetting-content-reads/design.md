# Design: collapse-vetting-content-reads

## Context

`QuarantineSnapshot` is the only implementation of `SnapshotUnderVetting` that
the running gateway uses. Its contract is deliberately narrow — a connector sees
paths and bytes of one pinned commit and nothing else — and that narrowness is
what the change has to preserve while making the reads cheaper.

Three existing properties constrain the shape:

- **Connectors run one at a time, each on an executor thread with a timeout**
  (`VettingService.runGuarded`). A connector that outruns its timeout is
  abandoned, not stopped — the class comment says so — so a *previous*
  connector's thread can still be inside `walk` while the next one starts.
- **Coverage gaps are reported, never dropped.** A blob over `max-file-bytes` is
  visited with `null` content so the connector can raise `file-not-scanned`
  (`GW_0143 — A clean vetting pass records what it examined`). Any change that
  skips files must not skip those.
- **Quarantined content is attacker-supplied.** Nothing in the vetting path may
  hold an amount of it proportional to what an upstream repository chose to
  contain.

## Goals / Non-Goals

**Goals**

- One tree walk per chain run instead of one per connector.
- A blob no connector asked for is never opened.
- A blob two connectors ask for is inflated once.
- Identical verdicts, byte for byte, from every connector.

**Non-Goals**

- Not a corpus index and not a search feature. Nothing is stored, nothing
  outlives the run, nothing is shared between snapshots. The corpus question is
  tracked separately (#153).
- Not a change to how connectors are scheduled. The push model — one walk
  driving every connector — would collapse the reads perfectly and is rejected
  below.

## Decisions

1. **The path selection is a `Predicate<String>` argument to `walk`, and that
   overload becomes the interface's primitive.**

   ```java
   void walk(Predicate<String> wanted, FileVisitor visitor) throws IOException;

   default void walk(FileVisitor visitor) throws IOException {
       walk(path -> true, visitor);
   }
   ```

   Each connector already ran exactly this test as the first statement of its
   visitor; the predicate moves it one step earlier, to before the blob is
   opened. That is why no verdict changes: the same paths reach the same visitor
   bodies with the same bytes.

   *Alternative rejected:* a `wants(path)` method on `FileVisitor`. It reads the
   same at the call site but makes every existing visitor — including the ones
   in tests — implement two methods instead of staying a lambda.

2. **The tree index is built once, in the constructor, and holds no content.**
   A `List<Entry(path, blobId)>` over the recursive `TreeWalk` of the pinned
   commit. Building it eagerly makes it a `final` field, which is what makes it
   safe for the abandoned-connector thread to read concurrently without a lock.
   It holds object ids, not bytes, so its size is proportional to the number of
   files rather than to their content.

   Note what it does *not* hold: the blob's size. Size comes from
   `repository.open(id).getSize()` at read time, which reads the object header
   and does not inflate. Storing it would mean opening every object during
   indexing — including the ones nobody asks for, which is the cost being
   removed.

3. **Content is cached per run, keyed by blob id, bounded by configuration.**
   `ConcurrentHashMap<ObjectId, byte[]>` guarded by an `AtomicLong` budget
   initialised to `skills-gateway.vetting.content-cache-bytes` (default 32 MiB).
   Keying by blob id rather than by path means two identical files — a licence
   copied into every plugin — cost one entry.

   Past the budget, content is read and returned but not retained. The
   degradation is speed, never coverage: there is no input for which a file is
   skipped because the cache was full.

   *Why bounded at all:* unbounded, the cache is `O(snapshot content)` held for
   the length of a run, and the snapshot is an upstream repository the gateway
   does not control. That is a memory-exhaustion primitive reachable by anyone
   who can get a marketplace registered. Today's peak is `O(largest file)`;
   after this change it is `O(bound + largest file)`, which is a number an
   operator sets.

   *Why a new property rather than a constant:* the right ceiling depends on the
   heap the gateway runs with and on how large the estate's marketplaces are.
   Both are deployment facts, and `max-file-bytes` — the only nearby number — is
   a per-file cap answering a different question.

4. **Rejected: one walk driving all connectors (the push model).** It is the
   only design that reads each blob exactly once with no cache at all, and it
   costs too much. `VettingService` deliberately runs each connector inside its
   own `Future` with its own timeout so that a connector that throws, hangs or
   ignores interruption becomes *that connector's* `ERROR` verdict and nothing
   else's. Driving every connector from one walk puts them on one thread and one
   clock: a hanging connector would stall the walk for the rest, and a connector
   that threw would abort a pass the others were partway through. The
   per-connector fail-closed isolation is worth more than the cache.

5. **Where each connector's selection lives.** With the connector, not in a
   shared table — the selection is part of what the connector reads, and a table
   would be a second place to keep in step:

   | Connector | Selection |
   | --- | --- |
   | `secret-scan` | everything |
   | `prompt-injection` | `.md`, `.mdc`, `.markdown`, `.txt` |
   | `license-scan` (`LicenseDetector`) | `LICENSE`/`COPYING`-shaped files, and `.claude-plugin/marketplace.json` |
   | external connectors | everything — the bundle ships the whole snapshot |

## Risks / Trade-offs

- **A connector under-declaring its selection silently sees less content.** The
  predicate is now the connector's own statement of what it reads, and a wrong
  one is a coverage gap with no error. Mitigated by the selection sitting in the
  same class as the visitor that consumes it — `LicenseDetector`'s predicate and
  its visitor test the same two constants — and by the connectors' own tests,
  which assert findings in files each connector must reach.
- **Memory grows from `O(largest file)` to `O(bound + largest file)` per run.**
  Accepted, bounded and configurable; see decision 3. Re-vetting sweeps run
  snapshots sequentially (`RevetService.sweep`), so concurrent runs are bounded
  by ingestion concurrency rather than by estate size.
