# Design: ingest-failures-say-why

## Context

For the motivation, see proposal.md, "Why". The relevant facts about the code
today:

- **The upstream fetch.** `IngestionService.fetchUpstreamHead` fetches
  `+HEAD:refs/quarantine/incoming` with JGit's default transport. On any
  `GitAPIException` it falls back to `Git.lsRemoteRepository()` and fetches
  the resolved branch, discarding the first error. The whole of
  `ingestLocked` wraps `IOException | GitAPIException` as
  `IngestionException("ingestion failed for marketplace '%s'")`.
  `AdminController` maps that to a `502` whose detail is only the wrapper.
- **The guard it does not use.** The marketplace upstream fetch does **not**
  go through `GuardedHttpConnectionFactory`. That factory is installed only on
  external plugin-source fetches, and its own Javadoc says the marketplace
  upstream fetch was left alone on purpose. The upstream's trust controls are
  the registration allowlist (scheme) and the gateway-pinned ref.
- **Registration.** `MarketplaceRegistrationService.register` is the single
  registration gate for the API and for the estate reconciler, which treats a
  `ResponseStatusException` as an entry failure.
- **Recorded state.** `marketplaces.last_sync_at` stamps sweep attempts only,
  for queue order. Nothing records how an ingest ended.

## Goals / Non-Goals

**Goals:**
- One path to the upstream for both `ls-remote` and fetch.
- One translator from a failure to what an operator reads.
- A last-ingest record on the marketplace, and a ledger entry for every failed
  ingest.

**Non-Goals:**
- **Upstream credentials.** They are #494. This change leaves a seam for them.
- **Extending the SSRF address policy (`SourceAddressPolicy`) to marketplace
  upstreams.** Ingestion does not apply it today. Applying it would refuse the
  internal forges that enterprise deployments register, on private networks, by
  default. That is its own decision.
- A test-connection endpoint.

## Decisions

1. **`UpstreamGit` is the one door to a marketplace upstream.** It is a
   component in `ingestion` with two operations:
   - `probe(url)`: `ls-remote`, resolving the default branch.
   - `fetchDefaultBranch(repo, url, ref)`: the HEAD fetch with its `ls-remote`
     fallback.

   Both call one private `connect(command)`, which sets the transport idle
   timeout. That method is the seam where #494 will set a `CredentialsProvider`,
   so registration and ingestion cannot diverge. Registration therefore takes
   exactly ingestion's network path, and adds none of its own.
   - *Alternative:* install `GuardedHttpConnectionFactory` on both. Rejected for
     this change (see Non-Goals). It would also not cover `file://`, which the
     guard does not handle.

2. **The timeout is a fixed 60-second idle timeout, not a setting.** A
   registration request must not hang on a silent upstream, and the fetch
   shares the connect path. JGit's timeout is an idle read timeout, not a total
   one, so a large but moving fetch is unaffected. A configuration leaf would
   need a raise of `ConfigSurfaceBudgetTests` that nothing here justifies.

3. **`UpstreamFailure` is the translator.** It is a record of `reason`,
   `nextStep` and `rootCause`, built by `UpstreamFailure.of(Throwable)`, which
   walks the cause chain **and suppressed exceptions**.
   - **Classification order**, first match wins across the whole walk:
     1. not found or unauthorised (`NoRemoteRepositoryException`, or JGit's
        `notAuthorized` / `noCredentialsProvider` / `authenticationNotSupported`
        / `serviceNotPermitted` text);
     2. unknown host;
     3. could not connect or timed out;
     4. TLS;
     5. otherwise "the upstream fetch failed".
   - **The root cause** is the innermost message of the throwable that decided
     the class, so a fallback's generic error cannot hide the specific first
     one.
   - **No default branch** is not a JGit error. `UpstreamGit` raises it
     directly as `UpstreamFailure.noDefaultBranch()`.
   - **Redaction.** Userinfo in any URL in the root cause is replaced with
     `***`. A credential never reaches the response, the row, the log or the
     ledger.
   - **Why a record and not only a message:** the `502` carries its parts as
     problem properties, and the marketplace row and the ledger store its
     one-line `describe()`.

4. **The fallback keeps the first error.** `fetchDefaultBranch` catches the HEAD
   fetch's error. If the fallback also fails, it calls
   `second.addSuppressed(first)` and translates the second error, which rule 3
   then reads including the suppressed one. The thrown `UpstreamException`
   keeps the chain as its cause.

5. **Failures are translated only where they are upstream failures.** For an
   upstream marketplace, `IngestionService` translates only what `UpstreamGit`
   throws. Any other failure — hosted "not published yet", local storage,
   vetting — is recorded with reason "ingestion failed" and its root cause, and
   is not dressed up as an upstream answer.

6. **The last ingest is recorded in `IngestionService.ingest`, under the
   per-marketplace lock.** Every trigger path goes through it: manual,
   scheduler, webhook and push.
   - **Storage:** three columns, `last_ingest_at`, `last_ingest_outcome` (enum
     `succeeded` | `failed`) and `last_ingest_reason`, with a CHECK that the
     three are recorded whole and that a reason exists exactly when the outcome
     is `failed`.
   - **On failure:** the service writes the row, appends `ingest-failed` to the
     ledger with the triggering actor and `describe()`, logs WARN, and rethrows.
   - A snapshot rejected by manifest policy counts as a *succeeded* ingest: the
     fetch worked, and the rejection is on the snapshot.
   - *Alternative:* record in each caller. Rejected, because four call sites
     would drift.

7. **Registration probes last.** The name, reserved-name, origin, push-policy,
   scheme and name-conflict checks run first, so a request refused on those
   never contacts the upstream. On failure,
   `register(..., Reachability.REFUSE)` throws `UpstreamException`, which
   `AdminController` answers with `502`. Nothing is written, and storage
   cleanup (`startFromNothing`) has not run.

8. **The estate reports instead of refusing.** The reconciler calls
   `register(..., Reachability.REPORT)`.
   - When the probe fails, the marketplace is still registered.
   - The registration service appends `marketplace-upstream-unreachable` to the
     ledger (actor `config-reconciler`, detail `describe()`) and returns the
     failure as a warning. The reconciler puts that warning in the entry's
     detail. The entry stays `created`.
   - Why not `failed`: the object exists and converged. The upstream is what is
     unhealthy, and the first ingest records that too.

9. **The problem detail.** For any `IngestionException` carrying an
   `UpstreamFailure`, the `502` sets:
   - `title`: "Upstream fetch failed", or "Upstream not readable" for a
     registration;
   - `detail`: "<context>: <describe()>", which the portal already shows in its
     toast;
   - properties `reason`, `rootCause` and `nextStep`.

10. **Null principal.** `AdminAuditLogger` did `Set.of(...).contains(null)`,
    which throws. It is guarded, so an ingest with no recorded actor (test
    arrangements) can still ledger its failure.

## Risks / Trade-offs

- **[Registration now needs the upstream to be up]** → That is intended. The
  estate is exempt, so startup never depends on it.
- **[The registration request can take up to the idle timeout]** → It is
  bounded at 60 s. A dead host fails fast on connection refused, and a
  blackholed one hits the timeout.
- **[Message matching on JGit text]** → The matching compares against
  `JGitText` itself, not copied strings, and U1 pins the behaviour against the
  JGit version the build uses.
- **[Existing tests used unreachable placeholder URLs]** → They move to local
  fixtures, with their assertions unchanged.

## Migration Plan

Pre-1.0: `V1__init.sql` is edited, so existing databases are recreated. No
rollback beyond reverting the change.
