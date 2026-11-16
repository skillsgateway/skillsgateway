# Design: marketplace-auto-approval

## Context

See proposal.md, Why. Today:

- **Holding and approving.** `IngestionService.ingestLocked` creates a snapshot
  `HELD`, and `VettingService.vet` vets it synchronously. Neither runs in a
  transaction; only the repositories are `@Transactional`.
  `VettingService.announceIfAwaitingApproval` emits the approval-pending webhook.
  Only `POST /api/v1/snapshots/{id}/approve` approves a snapshot.
- **`ApprovalService.doApprove` runs every gate**, in this order:
  1. closure completeness;
  2. standing revocation;
  3. effective vetting outcome;
  4. policy;
  5. name collision;
  6. release age;
  7. four-eyes.

  It then publishes under `PublicationLock`, with a compare-and-set in
  `SnapshotRepository.decide`.
- **The approval's evidence is written by the controller.** After
  `ApprovalService.approve` returns, `AdminController.approve` writes:
  - the `waiver-applied` entries;
  - `snapshot-approved`;
  - the vetting-override and superseded-chain entries;
  - the warn-mode `four-eyes-conflict` entry;
  - the `SNAPSHOT_APPROVED` webhook.

  A second caller of `ApprovalService` would silently skip all of it.
- **Nothing retries an approval.** `ReleaseAgeGate` is evaluated per request
  only.
- **The vetting outcome.** `WaiverEvaluation.evaluate` yields `CLEAR`,
  `CLEAR_WITH_WAIVERS` or `BLOCKED`. Low and medium findings warn and clear. A
  run clears when at least one verdict clears, so a `DISABLED` vetter neither
  blocks nor counts. `ChainStaleness.stale()` is true only for `SUPERSEDED`; an
  `UNDETERMINED` chain reads as not stale.
- **External sources.** Each one is grafted into the composite commit at its
  current `HEAD` (`ExternalSourceResolver`), and recorded as a closure member
  with a `clone_url`. `SourceUrlPolicy.refuseRedirect` accepts same-host
  redirects.
- **The inventory is not a fail-closed reader.** `SnapshotContentService`
  lists hooks, hook modules, MCP and LSP servers. It reads a file over 1 MiB as
  `null` and skips plugins past `ManifestPolicy.MAX_PLUGINS`. `content-diff`
  compares only plugins and skills.
- **URL prefix matching exists, but privately.** `UpstreamCredentials` has a
  sound prefix matcher: a normalised scheme, host and port, rejection of encoded
  separators, and segment-boundary prefixes. It is a private record.
- **System actors.** `AdminAuditLogger.SYSTEM_ACTORS` types named system actors
  `system`. `FourEyesGate` compares identities with exact string equality and
  excludes only `scheduler` and `webhook`.
- **Re-vetting.** `RevetScheduler` runs every `revet.interval` (default 6h)
  while `revet.enabled` (default `true`). It revokes on a violation only under
  `revet.mode=enforce` (default `warn`), which is global.

Stakeholders:

- an adopting organisation that wants repository-manager ergonomics for its own
  repositories;
- its security and compliance function, which needs an accountable trail and
  fast recall.

## Goals / Non-Goals

**Goals:**

- No person has to click to publish a clean update of a fully internal
  marketplace whose executable surface did not change.
- Trust never travels from a marketplace to anything it pulls in.
- Any doubt, including any read that did not complete, leaves the snapshot held
  for a person, exactly as today.
- An automatic approval records the same evidence as a human one, plus what
  allowed it.
- Whatever no person approved is withdrawn on new evidence without waiting for
  a person.
- The adopter can watch what the gateway *would* approve before it does.

**Non-Goals:**

- **Risk tiers or trust levels.** Findings stay the risk model (ADR 0021).
- **Snapshots with external sources.** A later change may accept an external
  member whose `(clone_url, tree_sha)` a person already approved in the same
  marketplace, alongside ADR 0011's declared `ref`/`sha` pins.
- **Automatic approval of revoked snapshots.** A reversal still needs a second
  administrator (`GW_APPROVAL_0017 — A snapshot an administrator revoked is
  approved again only on a recorded reversal by a second administrator`).
- **A configurable severity threshold.**
- **Portal surfaces beyond the automatic approval card (D15).** The bulk withdrawal and
  the per-snapshot evaluation stay API-only in this change.
- **Content-scoped waivers and rule tuning.**

## Decisions

### D1 — Automatic approval is a marketplace field with a grantor, a reason and an expiry; what allows it is computed

The automatic approval record on `marketplaces` is:

- `auto_approval_enabled`: `false` (default) or `true`;
- `auto_approval_set_by`, `auto_approval_reason`, `auto_approval_expires_at`.

Turning it on always needs a reason and an expiry of at most 90 days, whatever
allows it. When the expiry passes, automatic approval is effectively off. Renewal is
a new set, with a new reason and expiry. This is the recertification a
regulated adopter needs.

Ninety days is the gateway's limit for every expiring grant a named person
makes: a waiver (`WaiverService.MAX_LIFETIME`) and, by default, a machine
credential (`Tokens.DEFAULT_MACHINE_MAX_TTL`). The setting and the exemption
(D3) each get their own constant with that value, rather than sharing one, so
that changing one limit is a decision about that grant alone.

Alternative considered: 365 days for an internal source. Rejected: it would be
the only grant on a yearly cycle, and nothing about an internal source makes a
quarterly renewal a burden.

Whether automatic approval takes effect is computed at each attempt, as
`allowedBy`:

- **`internal-source`:** the URL is under a configured prefix;
- **`hosted`:** the origin is `hosted`;
- **`exemption`:** an active administrator exemption exists;
- **otherwise `none`,** handled as off.

Removing a prefix from the configuration, or the setting or exemption expiring,
takes effect at the next attempt, with no repair step.

Alternative considered: a stored "is internal" flag. Rejected, because it
would go stale when the prefix list changes, and a stale "trusted" bit fails
open.

### D2 — Internal sources: one shared, fail-closed URL-prefix matcher

`UpstreamCredentials.Target` is extracted into a shared `UrlPrefix` in
`ingestion`, so credentials and internal sources normalise URLs identically. A
URL is internal when it falls under a configured prefix:

- the same scheme, host and port;
- a path equal to the prefix, or starting with it followed by `/`.

`https://github.com/acme` never covers `https://github.com/acme-evil`.

Startup refuses two kinds of prefix:

- one that does not parse;
- one with no path segment, both on a multi-tenant forge host (`github.com`,
  `gitlab.com`, `bitbucket.org`) and on the configured
  `external-sources.github-base-url` host. A host-wide prefix would make every
  user's namespace internal.

Hosted marketplaces are internal by construction: their content arrives only
through `/publish/**`, with push scope that an administrator or that
marketplace's approver minted.

The URL is what was registered, not necessarily what was fetched: ingestion
follows a same-host redirect, which a forge sends for a transferred or renamed
repository. The first request that leaves the registered repository's path is
recorded on the snapshot with its row, and the attempt refuses that snapshot as
`redirected` (acceptance R22).

Alternative considered: reusing the credential prefixes. Rejected: holding a
credential for a URL is not trusting it.

### D3 — The administrator exemption: an expiring exception, never declared in the estate

Table `auto_approval_exemptions`:

- columns: `id`, `marketplace_id`, `reason`, `created_by`, `created_at`,
  `expires_at`, `revoked_at`, `revoked_by`, `expired_recorded_at`;
- `CHECK (expires_at <= created_at + interval '90 days')`, matching the
  exemption's own `EXEMPTION_MAX_LIFETIME` constant (D1);
- a partial unique index, so a marketplace has at most one active exemption.

Turning automatic approval on for a marketplace whose source is not internal
creates the exemption in the same request. The setting's own expiry is then
capped at the exemption's.

The exemption is not declarable in the estate, for the reason waivers are not:
it is a dated judgement by a named person, not standing configuration. It is
created and revoked through the API or the portal (D15).

With both limits at 90 days, the cap matters when the exemption is older than
the setting: renewing automatic approval never outlives the exemption it rests
on.

Three edges, each settled fail-closed during implementation:

- Turning automatic approval off revokes the active exemption, so turning it
  on again needs a new dated judgement and cannot reuse a stale one.
- An exemption for a marketplace whose source is already internal is refused
  (`400`). It would be an exception with nothing to except.
- A lapsed exemption stops counting at once, on every read. The
  `auto-approval-exemption-expired` entry is written by the first attempt that
  meets the lapse, through a guarded update, so it lands on the ledger once. A
  read never writes.

### D4 — Four-eyes on turning automatic approval on

Turning automatic approval on, whether for an internal source or by exemption,
is checked by
`FourEyesGate` as an approval would be. The grantor conflicts with the
marketplace's `registered_by` (`registered-by`):

- under `warn`, the conflict is recorded and automatic approval is set;
- under `enforce`, it is refused, so a second administrator must set it.

The choice stays in the registration request. Under `enforce`, automatic
approval chosen at registration is stored as requested but stays ineffective
(`allowedBy` `awaiting-second-administrator`) until a different administrator
confirms it with `PUT …/auto-approval`.

Estate-declared automatic approval is set by `config-reconciler`. Its separation of
duties is the review of the estate repository, outside the gateway, so the
reconciler is excluded from this check only. It is not added to
`FourEyesGate.NON_HUMAN_ACTORS`, which would also change approvals. The ADR
states this.

The automatic approval itself runs `FourEyesGate` unchanged. A system actor
matches no identity, so separation of duties lives in the grant (above), not in
the per-snapshot decision.

### D5 — Eligibility: only the newest snapshot, with no external source

A snapshot is a candidate only when all of these hold:

- it is `HELD`, not revoked and not deleted;
- it is the marketplace's newest non-deleted snapshot. An older held snapshot
  is never approved automatically, so the served tip cannot move backwards;
- its recorded closure has **no members**. A snapshot that pulls in any external
  source stays with a person, whatever that source's URL. Members are fetched
  at a floating `HEAD`, never registered, and can redirect within a host.

### D6 — The vetting predicate: the current chain clears, and every required vetter ran

All of these must hold:

1. **The chain is current.** `ChainStaleness.state` is `IN_FORCE`. `UNDETERMINED`
   and `SUPERSEDED` both fail.
2. **Nothing failed to run.** No verdict is `NOT_REACHED`, `ERROR` or
   `PENDING`, for any vetter. These are faults or unfinished work, not an
   operator's choice, so no setting relaxes them.
3. **Every required vetter ran.** A required vetter's verdict is not
   `DISABLED`. Switching vetters off stays allowed, as today; it only decides
   whether automatic approval can act. Without this rule, switching every
   vetter off but one would leave that one passing vetter as the only
   evidence, because a `DISABLED` verdict neither blocks nor counts
   (`GW_VETTING_0029 — The vetter on/off switch never becomes a blanket
   approval`).

   Which vetters are required is
   `skills-gateway.approval.auto.required-vetters`:
   - **unset (the default):** every vetter is required, so any `DISABLED`
     verdict keeps the snapshot held;
   - **a list of vetter names:** only those are required; a `DISABLED` verdict
     from any other vetter does not stop automatic approval.

   Startup refuses an empty list (it would require nothing) and a name that is
   not a configured vetter (a typo would silently require nothing). A required
   vetter that a deployment later removes from the chain leaves the rule
   unsatisfiable, so every snapshot stays held rather than the rule passing
   vacuously.

   The setting is gateway-wide, not per marketplace. A per-marketplace list
   would let whoever turns automatic approval on also lower the bar for that marketplace,
   and four-eyes on the grant (D4) would then be the only check on both.
4. **The effective outcome is `CLEAR` or `CLEAR_WITH_WAIVERS`.** Medium
   findings clear, as they do for a person.
5. **Waivers are restricted.** A high or critical finding suppressed by a
   waiver qualifies only if the served baseline's run carries the same rule id
   in the same blob, so the waiver was already in force when a person
   approved that content. A waiver cannot pre-clear new content.
6. **Unread surfaces fail closed.** No finding carries `hook-config-unreadable`,
   `hook-target-unscanned` or `too-many-plugin-roots`, whatever its severity.

### D7 — Executable surface: unchanged from the served baseline, read completely

The baseline is the served tip (`refs/heads/main` in the served repository).
The surface is unchanged when both of these hold:

1. **The declarations are equal.** The declarations are read by a new strict
   reader beside `SnapshotContentService`, which **fails** rather than returning
   `null` on an oversize file, past `MAX_PLUGINS`, or on a parse error. They are:
   - hooks (event, matcher, command), hook modules, and MCP and LSP servers
     (name, command, args, URL);
   - each skill's `allowed-tools` frontmatter and each agent's `tools`.

   A failed read means the surface is changed.
2. **Every added or modified path is inert.** Paths are compared with a JGit
   `TreeWalk` between the two trees. A path is inert only when all of these
   hold:
   - its mode is `100644`. A symlink (`120000`), a gitlink (`160000`),
     `100755`, or a mode-only change is not inert;
   - it is not a dependency manifest or lockfile known to `DependencyManifests`;
   - it is not a launch target named by a declaration;
   - it does not start with `#!`;
   - its extension is on a fixed **inert** list: `md markdown txt rst adoc png
     jpg jpeg gif webp ico pdf csv tsv`. `json yaml yml toml` are never inert,
     read or not: an unchanged script can read one, and a component kind no
     reader here knows can be declared in one (R14);
   - the marketplace manifest and a `plugin.json` are inert when every key, at
     the top level and in each manifest entry, is on a fixed descriptive list
     (name, version, description, author, owner, metadata, homepage,
     repository, license, keywords, category, tags, `source`, `plugins`,
     `$schema`). Any other key, `strict` and keys no reader examines included,
     makes the file not inert (R6). Without this, every first snapshot would be
     held, because every marketplace has a manifest. A changed `source` is not
     a loophole: the roots are read again, so what a new root declares reaches
     the comparison in (1).

   Everything else, including every unknown extension, counts as a script.
   Deletions do not count.

When there is no served baseline, the declarations must be empty and every path
must be inert.

The inert list is code. Widening it is a precision change, argued in a change.

Accepted residual risk, stated in ADR 0023: in an agent skill, Markdown is
instructions. A changed `SKILL.md` that raises no finding is approved
automatically. A dry run (D11) is the adopter's way to see how often that
happens before turning it on.

### D8 — `AutoApprovalService` uses the same approval path, as a system actor

It lives in the `approval` package. Its actor is `auto-approver`, added to
`SYSTEM_ACTORS`. One `attempt(snapshotId)` checks, in this order:

1. the mode (D11);
2. that re-vetting is enabled (D12);
3. what allows it (D1, D4);
4. the candidacy (D5);
5. the vetting predicate (D6);
6. the executable surface (D7).

It then calls `ApprovalService.approve(id, "auto-approver",
ApprovalOverride.none())`, which runs the closure, revocation, policy,
name-collision, release-age and four-eyes gates unchanged. `ApprovalService`
itself does not change.

Under `dry-run` the six checks run and those gates do not, since they run only
inside `approve`, which publishes. A would-approve entry therefore means
eligible, subject to the gates (acceptance R7).

A refusal from any gate leaves the snapshot held, and is recorded (D9). A race
with a person is settled by `decide`'s compare-and-set: the loser gets "changed
while it was being decided", and the automatic path records that as a skip.

### D9 — One recorder for every approval's evidence; skips stored on the snapshot

The post-approval block of `AdminController.approve` moves into
`ApprovalRecorder` in `approval`, which both paths call. The automatic path
adds:

- **`snapshot-auto-approved`**, recorded as `system`. Its detail carries:
  - what allowed it (the matched prefix, `hosted`, or the exemption id) and
    the grantor;
  - the chain identity, and the baseline SHA or `none`;
  - the worst severity and the waiver ids;
  - the vetters that were switched off, if any, so a relaxed
    `required-vetters` shows in each approval's evidence.
- **`snapshot-auto-approval-skipped`**, with the first failing check. Its reason
  is also stored in `snapshots.auto_approval_skip_reason`, and the ledger entry
  is written only when the reason differs from the stored one. Deduplication
  never reads the ledger.

Alternative considered: the automatic path writes its own entries. Rejected,
because two copies of "what an approval records" would drift. The human path's
output is pinned by a test before the move.

### D10 — Triggers: after ingestion, after a waiver or automatic approval change, and on the re-vetting tick

- **After `IngestionService.ingest` returns** a held snapshot, outside the
  ingestion lock. Re-vetting a held snapshot (`RevetService.refreshHeld`)
  triggers it the same way. Every trigger is a synchronous application event
  (`HeldSnapshotChanged`, and `RevetTick` for the tick): `AutoApprovalService`
  is constructed with `WaiverService`, so a direct call back would be a
  constructor-injection cycle. The listener is a
  `@TransactionalEventListener` with `fallbackExecution = true`, so it runs
  after the commit inside a transaction and at once outside one; nothing on the
  ingestion path runs in a transaction (acceptance R8).
- **After a waiver is created and after automatic approval is set**, for that
  marketplace's newest held snapshot. Set means through the API or by the
  estate reconciler applying a declared `enabled: true` (acceptance R20).
- **On each `RevetScheduler` tick**, one bounded step attempts the newest held
  snapshot of each marketplace with automatic approval on. This is what approves a snapshot once it
  reaches the minimum release age; the lag is at most `revet.interval`. Because
  only the newest snapshot per marketplace is attempted, a backlog of blocked
  old snapshots cannot starve the batch.

No new scheduled sweep is added.

### D11 — `off | dry-run | on` for the deployment

`skills-gateway.approval.auto.mode` is one of:

- **`off` (default):** no marketplace's automatic approval takes effect;
- **`dry-run`:** every check runs, the result is recorded as
  `snapshot-auto-approval-would-approve` or as a skip, and nothing is published;
- **`on`:** eligible snapshots are approved.

`off` is also the incident switch. `GET /api/v1/snapshots/{id}/auto-approval`
returns the same evaluation in every mode, which makes it the adopter's
measurement tool.

### D12 — Re-vetting always enforces for what no person approved

`RevetService` treats a snapshot approved by `auto-approver` as if `revet.mode`
were `enforce`, whatever the global mode. A violation revokes it through the
existing `quarantine` path, with the existing ledger entry and webhook.
Human-approved snapshots keep following the global mode, so a gateway upgrade
that adds a noisy rule cannot mass-withdraw content a person vouched for.

Automatic approval acts only while `revet.enabled=true`; otherwise it skips
with `revet-disabled`. Without the sweep, nothing would re-check auto-approved
content.

Alternatives considered:
- **Requiring global `enforce`.** Rejected, for that mass-withdrawal risk.
- **No requirement.** Rejected: unattended content needs unattended recall.

### D13 — Bulk withdrawal

`POST /api/v1/auto-approval/withdraw` with `{since, reason, serveAfter}` is
admin-only. It revokes every live snapshot approved by `auto-approver` at or
after `since`, through `RevocationService.revoke`, newest first, one marketplace
at a time under `PublicationLock`. It returns what it withdrew, and each
revocation is ledgered as today.

Every marketplace the request withdraws from has its automatic approval
turned off by the caller, with any open exemption revoked, and the response
names them in `turnedOff`: left on, the next push would be approved the same
way (R16).

### D14 — API and estate

- **Registration.** `POST /api/v1/marketplaces` accepts an optional
  `autoApproval: {enabled, reason, expiresAt}`. Enabling it for a source that
  is not internal is a `409`, with `nextStep` pointing at the exemption.
- **`GET /api/v1/marketplaces/{name}/auto-approval`** returns `enabled`,
  `allowedBy`, `setBy`, `reason`, `expiresAt`, and the active exemption.
- **`PUT /api/v1/marketplaces/{name}/auto-approval`** takes `{enabled,
  reason, expiresAt, exemption?: {reason, expiresAt}}`. It is admin-only.
- **`DELETE /api/v1/marketplaces/{name}/auto-approval/exemption`** revokes
  the exemption.
- **`GET /api/v1/snapshots/{id}/auto-approval`** returns the evaluation (D11).
- **Estate:** `skills-gateway.estate.marketplaces[].auto-approval.{enabled,
  reason, expires-at}`. The reconciler applies it as `config-reconciler`. It
  refuses, as a reconciliation failure, automatic approval enabled for a source
  that is not internal, and enabled with a missing or too-distant expiry. A
  lapsed declared expiry leaves automatic approval effectively off until the
  estate is changed.
- **Ledger:** `auto-approval-changed`, `auto-approval-exemption-granted`,
  `auto-approval-exemption-revoked` and `auto-approval-exemption-expired`.

Everything is additive under the API contract.

### D15 — Portal: an Automatic approval card on the Settings page

The marketplace Settings page (`MarketplaceSettingsPage`) gains an Automatic
approval card beside the existing administrator cards (vetting chain, remove).
It reads `GET …/auto-approval` and writes through `PUT` and `DELETE …/exemption`.
The portal adds no rule of its own; every check stays on the server.

- **Everyone who can see Settings** sees whether automatic approval is on,
  what allows it (including `awaiting-second-administrator` and `none`), the
  grantor, the reason, the expiry, and the active exemption with its reason and
  expiry. A lapsed setting or exemption reads as lapsed, not as active.
- **An administrator** can:
  - turn automatic approval on or off, with a reason and an expiry date no
    more than 90 days out;
  - for a marketplace whose source is not internal, grant the exemption in the
    same form, with its own reason and expiry;
  - renew either one;
  - revoke the exemption, behind a confirmation.
- **The gateway mode** is shown on the card, so automatic approval turned on
  under mode `off` or `dry-run` does not read as publishing.
- **Server refusals** (a `400` on a missing reason or a distant expiry, a `409`
  with `nextStep`, a four-eyes refusal) are shown as returned. The form does
  not pre-empt them with its own copy of the limits beyond the date picker's
  maximum.

The card follows `.claude/skills/design-conventions` and the shadcn/Base UI
components the vetting-chain card uses. It ships with Storybook stories for
each state (off, on internal, on by exemption, awaiting a second
administrator, lapsed, non-administrator view) and one e2e test.

## Risks / Trade-offs

- **[A compromised internal repository, or a stolen credential, publishes
  without a person.]** Mitigations:
  - no external sources;
  - the executable-surface rule;
  - the whole-chain clearing rule;
  - waivers limited to the baseline;
  - every existing gate;
  - automatic revocation on re-vetting;
  - bulk withdrawal;
  - the mode switch.

  The residual risk is a malicious instruction-only change with no finding,
  accepted in ADR 0023.
- **[A prefix misconfiguration makes a whole host internal.]** Startup refuses
  host-wide prefixes on multi-tenant hosts and on the `github-base-url` host.
- **[`required-vetters` names too few vetters.]** That is a stated operator
  choice, and each automatic approval's ledger entry names the vetters that
  were switched off (D9).
  Startup refuses the two silent forms, an empty list and an unknown name.
- **[`github-base-url` is global.]** On a GitHub Enterprise deployment,
  `owner/repo` shorthand resolves to the internal host. This change accepts no
  external sources at all, so the issue does not reach automatic approval. It is
  recorded for the external-source change.
- **[Release-age promotion lags by up to `revet.interval`.]** Documented. An
  adopter that wants less lag shortens the interval.
- **[The `AdminController` refactor changes the human approval's evidence.]**
  The human path's output is pinned by a test before the move.
- **[Four-eyes `enforce` with one administrator means automatic approval
  never takes effect.]** This is the same trade-off as approvals have, documented the same
  way.
- **[A new snapshot with a single benign change waits for a person, and blocks
  automatic approval of later ones until it is decided.]** Intended: only the
  newest snapshot is a candidate, so a later clean snapshot is the next
  candidate.

## Migration Plan

Pre-1.0: the schema change edits `V1__init.sql`, and existing databases are
recreated. The defaults (automatic approval off, mode `off`) keep today's behaviour.
Rollback is `mode: off`, plus bulk withdrawal for anything already published.

## Open Questions

- Is the inert extension list complete enough for real marketplaces? A dry run
  on the adopter's corpus answers this before `on`.
- Should an automatic approval expiry warn ahead of time, for example via a
  webhook 14 days before? This design does not; the automatic approval
  endpoint shows the date.
