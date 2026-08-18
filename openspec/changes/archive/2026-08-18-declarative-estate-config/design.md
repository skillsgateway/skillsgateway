# Design: declarative-estate-config

## Context

The estate objects the issue names already have exactly one audited creation
path each: `AdminController.registerMarketplace` (name pattern, reserved
catalog name, URL scheme allowlist — GW_0016/GW_0063), `RoleController.grant`
(role vocabulary, approver scoping to an existing marketplace — GW_0071),
`WebhookController.create` (name pattern, scheme allowlist, event-filter
validation, gateway-generated show-once secret — GW_0024), and
`AuditController.createSink` (sink = webhook subscriber filtered to
`audit.export` plus a cursor row — GW_0028). All of them audit through
`AdminAuditLogger` onto the append-only ledger. The reconciler must be a new
*caller* of these paths, never a second implementation of them.

## Goals / Non-Goals

**Goals:** a GitOps deployment defines its whole estate in configuration; the
declaration converges at startup and on demand; convergence is additive,
idempotent, audited, and failure-isolated; secrets for declared receivers are
operator-supplied by reference and never disclosed.

**Non-Goals:** PATs (user-owned credentials stay API-only); authoritative/
prune mode (below); webhook sync mode for declared marketplaces (below);
scheduled re-reconciliation; portal UI; IdP group-to-role mapping (#66 —
compatibility considered below).

## Decisions

Each decision below was argued against (grill-me) and resolved from the
codebase; the losing branch is recorded where it was a near call.

1. **Additive-only reconciliation; authoritative mode is a non-goal.** A
   declared object is created if missing and updated to match if drifted; an
   object absent from the declaration is never touched. The house principle
   is explicit (retention refuses served content, re-vet defaults to warn):
   *retracting content teams depend on must never begin because of an
   upgrade* — and "the ConfigMap lost a line" is the canonical upgrade
   accident. Drift the other way (API-created objects not in the file) is
   visible in the ledger and the listings; pruning it is a human decision.
   *Rejected branch:* an opt-in `prune` flag in this change — it doubles the
   test surface (every object type needs refuse-to-touch-served semantics
   mirroring retention's veto) for a mode the driving deployment does not
   need. Recorded as a possible follow-up behind an explicit default-off
   flag.

2. **A failing entry reports; it never bricks startup.** The application
   starts with an invalid declared entry; the entry is skipped, logged at
   ERROR, appended to the ledger as `estate-reconciliation-failed` (with the
   reason, never a secret), and carried in the reconciliation report that
   `GET /api/estate` answers. *Rejected branch:* fail startup on any invalid
   entry — fail-closed reads well until a typo in one marketplace name takes
   the whole gateway (and everything it serves to CI) down in a crash loop;
   the gateway's job is serving already-approved content, and a broken
   *declaration* must not retract a working *estate*. The opposite branch —
   silently skipping — hides drift; the ledger entry per failed entry per run
   is deliberate: a reconcile that cannot converge is not a no-op, so it is
   not covered by the zero-writes guarantee, and a boot is exactly when an
   operator reads the ledger. Infrastructure failures (database down) still
   fail startup: only *entry validation* is isolated.

3. **Reconcile runs after migrations, before the web surface serves.**
   `EstateBootstrap` implements `SmartInitializingSingleton`: it runs after
   every singleton is instantiated (Flyway has migrated — the repositories it
   uses depend on the migrated `DataSource`) and before the lifecycle phase
   that starts the web server. A declared estate is therefore in force from
   the first request — a fresh GitOps deployment never serves a window in
   which declared approvers are denied (deny-by-default would 403 them) or
   declared sinks miss ledger entries. The only network call on this path is
   the forge-metadata probe registration already makes, best-effort with a
   3-second timeout (`ForgeMetadataService`), so startup cannot hang on it.
   *Rejected branch:* `ApplicationRunner` — it runs after the web server
   accepts traffic, opening exactly that window for no benefit.

4. **The reconciler calls the same services the API calls — extracted, not
   duplicated.** The creation flows currently live in controller methods, so
   they are extracted into their services with behavior unchanged:
   `MarketplaceRegistrationService.register(name, url, actor)` (name pattern,
   reserved name, scheme allowlist, duplicate check, forge metadata, audit),
   `RoleService.grant(principal, role, marketplace, actor)`,
   `WebhookService.register(name, url, events, secret, actor)` (null secret →
   gateway-generated, as today), `AuditExportService.registerSink(name, url,
   after, batchSize, secret, actor)`. Controllers become thin delegates; the
   services keep throwing `ResponseStatusException` (house style —
   `RoleService` already does), which the reconciler catches per entry and
   turns into a report line. The existing SVC suites hold the endpoints'
   behavior across the extraction. *Rejected branch:* reconciler-private
   validation copies — the registration allowlist is a trust boundary, and
   two copies of a boundary drift into a hole.

5. **The estate block has no ref field, and declared sync modes exclude
   `webhook`.** Registration never accepts a ref (GW_0017: the gateway pins
   the upstream default branch), so the declaration cannot express one —
   the invariant holds structurally, not by validation. A declared
   `sync-mode` may be `on-demand` or `scheduled` and is applied through
   `SyncService.changeMode` (same audit event); `webhook` is refused as an
   entry failure because enabling it generates the inbound HMAC secret and
   returns it exactly once — a show-once secret has no declarative form, the
   same reasoning that keeps PATs API-only. An omitted `sync-mode` means
   "not managed": the reconciler never touches the stored mode, so an
   operator's API change survives reconciles.

6. **A declared marketplace whose stored URL differs is a failure, not an
   update.** The API deliberately has no URL-update endpoint: swapping the
   upstream URL swaps the supply chain under already-approved snapshots.
   The reconciler must not acquire a power the API refuses to have. The
   entry reports `failed` with the mismatch (drift made loud); the remedy is
   a human decision (new marketplace, or out-of-band migration).
   *Rejected branch:* update-to-match — it converts a config edit into a
   silent supply-chain swap behind approved SHAs.

7. **Declared grants may reference API-registered marketplaces.** An
   approver grant's marketplace must exist *at reconcile time* — declared
   earlier in the same file (marketplaces reconcile first) or registered via
   the API at any point before. Requiring same-file declaration was
   rejected: it would force a deployment migrating to GitOps to declare its
   whole marketplace estate before it may declare a single grant, and the
   API applies exactly the exists-now rule (GW_0071), which the reconciler
   reuses. A grant whose marketplace does not exist is an entry failure
   (reported, retried next reconcile), never a startup failure.

8. **Secrets by reference, with a floor, never disclosed.** Declared
   subscribers and sinks carry `secret` as an ordinary property value, which
   an operator supplies via `${ENV_VAR}` placeholder or an externalized
   property source — the gateway never generates and never shows it. A
   blank secret or one shorter than 16 characters is an entry failure (a
   trivial HMAC key would silently weaken every signature; 16 is a floor,
   the docs recommend 32+ random bytes as the API generates). Secret values
   never appear in the report, the log, the ledger, or any API response —
   rotation is audited as `webhook-subscriber-updated` /
   `audit-sink-updated` with a `secret rotated` marker, not a value. A
   changed referenced value updates the stored secret idempotently: same
   value → no write, no ledger entry. *Rejected branch:* an unresolved-
   placeholder heuristic (refusing values containing `${`) — Spring already
   fails startup on unresolvable placeholders, and `${VAR:}` defaults land
   in the blank check.

9. **Updates converge url/events/secret (subscribers), url/secret/batch-size
   (sinks); a sink's cursor is creation-only.** The cursor is runtime
   progress, not desired state — re-applying a declared `after` on every
   boot would re-deliver the ledger from that point on every deploy. `after`
   seeds the cursor at creation and is ignored afterwards (documented).
   Existing marketplaces converge only `sync-mode` (decision 6); grants have
   no updatable fields (a grant is its identity).

10. **Idempotency is diff-then-write, and the no-op is provably silent.** The
    reconciler compares desired to actual and touches storage only for
    differences; a converged estate produces zero writes and zero ledger
    entries — asserted by a dedicated SVC test, because audit spam on every
    boot would poison the ledger for its consumers (sinks re-export
    everything appended). Every *applied* change audits under the API
    equivalent's event name with actor `config-reconciler`, so ledger
    consumers distinguish declarative from interactive changes by actor, not
    by guessing. The on-demand endpoint additionally audits
    `estate-reconcile-triggered` with the *calling admin* as actor — the
    trigger is an admin action (GW_0022) even when the run converges nothing.

11. **Two endpoints, classified in the deny-by-default walk.**
    `POST /api/estate/reconcile` is a mutation → admin-only.
    `GET /api/estate` returns the last reconciliation report (in-memory; the
    startup run repopulates it after a restart) → auditor-or-admin, because
    like the subscriber/sink listings it exposes operator infrastructure
    (declared names, target URLs in failure reasons), classified with the
    operational listings per GW_0070. Both are added to
    `RoleEnforcementTests`' route classification, which the walk asserts
    against the live route table. *Rejected branch:* a health-indicator
    surface for failures — a config typo flipping readiness would let
    Kubernetes crash-loop the pod, re-creating the fail-closed brick that
    decision 2 rejects; the report endpoint, the ERROR log and the ledger
    are the visibility surface.

12. **Concurrency: one reconcile at a time.** `EstateReconciler.reconcile`
    is synchronized — startup and an eager admin cannot interleave partial
    writes; the database's unique constraints backstop the race anyway
    (a lost race surfaces as a duplicate-key entry failure, reported, and
    the next reconcile converges). Scheduled re-reconciliation stays out of
    scope, so contention is human-scale.

13. **#66 compatibility (IdP group-to-role mapping).** The grants list is a
    list of `{principal, role, marketplace}` records keyed by all three
    fields. A future group mapping arrives as a *sibling* key (e.g.
    `estate.group-mappings` or a `group:` principal namespace) without
    changing this schema; the role vocabulary is shared via `RoleGrant`'s
    constants, so a new grantable role extends one set. Per the standing
    CLAUDE.md note added in this PR, any future API-managed estate object
    type must extend this block in the same PR.

## Risks / Trade-offs

- [Extraction refactor touches four controllers on a trust boundary] → the
  moved code keeps its `@Requirements` annotations and identical
  `ResponseStatusException` statuses; the existing SVC suites (registration
  validation, grant validation, receiver creation) run unchanged and hold
  the behavior; no test assertion is modified.
- [A failing entry recurring on the ledger every boot] → deliberate
  (decision 2): an unconverged declaration is operator-actionable drift,
  and boots are rare; the zero-noise guarantee is scoped to converged
  entries, which is the steady state.
- [Reconciler races an interactive admin] → synchronized method plus
  database unique constraints; worst case is a reported entry failure that
  the next reconcile converges.
- [Declared secret in plain `application.yaml`] → the docs make the
  `${ENV_VAR}` reference pattern the documented form and state plainly that
  the property value is sensitive wherever it is stored; the gateway cannot
  distinguish how the value was supplied, so this is an operator contract,
  stated, not enforced.

## Migration Plan

No schema change. The block defaults to empty, and an empty declaration
reconciles nothing — zero behavior change on upgrade. Adopting: declare the
estate, deploy; existing API-created objects with matching names converge
(same URL → unchanged; drifted secrets rotate to the declared value).
Rollback: remove the block; nothing is deleted (additive-only).

## Open Questions

None; authoritative/prune mode and scheduled re-reconciliation are declared
follow-ups.
