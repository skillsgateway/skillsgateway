# Design: upstream-sync-modes

## Context

Ingestion is a single manual admin endpoint (`POST /api/marketplaces/{name}/ingest`
→ `IngestionService.ingest`), which fetches upstream HEAD into quarantine, pins the
SHA, records a held/rejected snapshot, and runs the vetting chain. Everything
downstream of the trigger (dedup by SHA, manifest policy, vetting, approval gate) is
exactly what this change must NOT touch: sync modes are new triggers in front of the
same pipeline. Sweep-style background jobs already exist (`RetentionScheduler`,
re-vetting sweep) and set the house pattern: `@Scheduled` + `enabled` flag + bounded
oldest-first batches + failures logged, never thrown.

## Goals / Non-Goals

**Goals:**

- Per-marketplace sync mode: `on-demand` (default), `scheduled`, `webhook`.
- Scheduled polling sweep with bounded oldest-first batches (`last_sync_at`).
- Inbound forge webhook endpoint that authenticates with a per-marketplace
  HMAC-SHA256 secret and triggers ingestion of the registered URL only.
- Explicit, tested upstream-outage resilience: a failing upstream never affects
  what the facade serves.
- Full audit trail: mode changes, sync-triggered ingestions with their trigger.

**Non-Goals:**

- Portal UI for sync mode (follow-up; API-only here, portal keeps working).
- Per-marketplace poll intervals or cron expressions (one global interval).
- GitLab `X-Gitlab-Token` plain-token auth (HMAC only; GitHub and Gitea both send
  `X-Hub-Signature-256`).
- Parsing the webhook payload (branch filters, event types). See Decisions.

## Decisions

1. **The webhook payload is untrusted and ignored.** A valid signature means one
   thing: "poll this marketplace now". The gateway ingests the registered upstream
   URL's default branch, exactly as a manual ingest would. Nothing from the request
   body (URLs, refs, SHAs) is read. This keeps the new endpoint from ever widening
   the trust boundary: the worst a forged-but-signed request can do is cause a fetch
   that lands a held snapshot — the same thing the schedule does. Alternative
   (parse GitHub push payloads, filter to the default branch) rejected: it adds a
   parser for attacker-supplied input for a marginal saving of redundant fetches
   that SHA dedup already makes cheap.

2. **HMAC-SHA256 over the raw body, GitHub-compatible header.** Verification reads
   `X-Hub-Signature-256: sha256=<hex>` and compares with `MessageDigest.isEqual`
   (constant-time). GitHub, Gitea, and Forgejo all emit this natively; other forges
   can send any body signed the same way. Alternative (bearer token in a header)
   rejected: HMAC binds the secret to the request body and never puts the secret on
   the wire.

3. **The secret is stored as plaintext in the database, shown once on enable.**
   HMAC verification needs the key itself, so PAT-style hashing is impossible. The
   DB is inside the trust boundary; the API never returns the secret after the
   response that created it (the marketplace view exposes only whether one is set).
   Switching mode to `webhook` (re-)generates the secret (32 random bytes, hex) —
   re-enabling is also the rotation mechanism. Switching away clears it.

4. **Mode is set via a dedicated endpoint, not registration.**
   `PUT /api/marketplaces/{name}/sync` with `{"mode": "scheduled"}` returns the
   updated marketplace, plus `webhookSecret` exactly once when the new mode is
   `webhook`. Registration stays untouched (new marketplaces are `on-demand`);
   keeping the secret-bearing response on a single endpoint keeps the audit story
   and the OpenAPI contract simple.

5. **Scheduled sweep = house sweep pattern.** `SyncScheduler` (`@Scheduled`,
   `skills-gateway.sync.poll-interval`, default 10m; `enabled` default true — the
   sweep is a no-op until an operator opts a marketplace into `scheduled`, so on
   upgrade nothing changes). Each tick takes up to `batch-size` (default 10)
   `scheduled` marketplaces ordered by `last_sync_at NULLS FIRST` and ingests each.
   `last_sync_at` is stamped per attempt (success or failure) so one dead upstream
   cannot starve the rest of the batch order.

6. **Webhook-triggered ingestion is asynchronous; concurrency is solved at the
   choke point.** The endpoint verifies the signature, responds `202 Accepted`,
   and queues the ingest on a single-threaded executor (forges time out
   deliveries in ~10s; an upstream fetch can take longer). But the executor only
   serializes webhook triggers against each other — a manual admin ingest or a
   scheduler tick can still overlap, and `IngestionService.ingest` is not safe
   for concurrent same-marketplace calls (JGit ref-lock races on
   `refs/quarantine/incoming`; the exists-check races the
   `UNIQUE (marketplace_id, sha)` insert into a `DuplicateKeyException`). So the
   fix lives in `IngestionService.ingest` itself: a per-marketplace lock
   (`ConcurrentHashMap` of locks keyed by marketplace id) covering every trigger
   path, plus treating a unique-violation on insert as "return the existing
   snapshot" for belt-and-braces. The scheduler uses the same trigger service as
   the webhook path so both share the audit/emit code.

7. **Trigger identity in the ledger.** Sync-triggered ingestions are audit-logged
   like manual ones, with actor `scheduler` or `webhook` and action
   `snapshot-ingested`, and emit the existing `snapshot.ingested` lifecycle event.
   Mode changes log `sync-mode-changed` with the acting OIDC identity. (House
   precedent: retention's `POLICY_ACTOR`.)

8. **Security config: a dedicated stateless chain for `/hooks/**`.** The web
   chain is session-based with CSRF enabled (ignored only for `/api/**`) and
   `anyRequest().authenticated()`, so "permit through the OIDC chain" would 403
   on CSRF before the controller runs. Instead: a new `@Order(2)`
   `SecurityFilterChain` with `securityMatcher("/hooks/**")`, CSRF disabled,
   `SessionCreationPolicy.STATELESS`, `permitAll()` — mirroring the facade's
   `gitChain` — and the web chain moves to `@Order(3)`. Authentication is solely
   the HMAC check inside the controller (which also keeps the gate intact under
   `dev-insecure-auth=true`); the controller takes no `Authentication`
   parameter. The facade chain (`/git/**`, `@Order(1)`) is untouched. HMAC
   computation reuses the existing outbound `WebhookSigner` (same
   `sha256=<hex>` form), compared with `MessageDigest.isEqual`. Responses:
   unknown marketplace → 404; marketplace not in `webhook` mode → 404; bad or
   missing signature → 403 with no detail; body over 1 MiB → 413 (bounds the
   HMAC input). The 403-vs-404 split lets an unauthenticated caller distinguish
   "exists in webhook mode" from "doesn't" — accepted: forge delivery logs need
   the distinction for debugging, and the name reveals nothing servable.
   Negative tests cover each response.

## Risks / Trade-offs

- [Plaintext secret in DB] → unavoidable for HMAC; DB is in-boundary, secret is
  per-marketplace, rotation is one API call, and it can only trigger a fetch of the
  registered URL — never publication.
- [Webhook endpoint is a DoS surface] → work per request before auth is one DB
  lookup + one HMAC over a ≤1 MiB body; ingestion is queued on one thread and SHA
  dedup collapses repeats. No retry/amplification.
- [Scheduled sweep hammers a slow upstream] → bounded batch, `last_sync_at`
  stamped on failure too, JGit fetch failures logged and retried next eligible tick.
- [A live scheduler pollutes tests] → house pattern: `skills-gateway.sync.enabled=false`
  in the shared test properties; tests drive `SyncService` directly, and the
  webhook tests await the queued ingest deterministically (synchronous executor
  injected in the test context).
- [202 hides ingest failures from the forge] → by design; failures land in the log
  and the next schedule/manual ingest retries. The forge's delivery log still shows
  2xx = "gateway heard us", which is true.

## Migration Plan

Schema folds into `V1__init.sql` (house rule; Testcontainers recreate per run):
`sync_mode TEXT NOT NULL DEFAULT 'on-demand'` + CHECK, `webhook_secret TEXT`,
`last_sync_at TIMESTAMPTZ`. Existing rows get the default; no behavior change until
an operator changes a mode. Rollback = revert the PR (columns unused by old code).

## Open Questions

None — payload parsing, portal surface, and per-marketplace intervals are declared
follow-ups in the proposal.
