# Proposal: a marketplace can be approved automatically when everything in it is internal

## Why

Every snapshot is held until a person approves it, including one that vets
clear and comes from the adopting organisation's own repositories. The first
organisation preparing to adopt the gateway uses it the way it uses a
repository manager such as Artifactory or Nexus: one governed source for mostly
internal skills and plugins. For that use the per-version click is pure cost,
and it is the reason they cannot adopt today. ADR 0021 (risk is expressed as
findings, not tiers) names this as the decision that would reopen it, and the
predicate to try first: a findings threshold, not a tier.

The danger is that internal content is not automatically safe, and an internal
marketplace can pull in external repositories. Trust must never travel from
the marketplace to what it pulls in, and whatever no person approved must be
recallable without waiting for a person.

## What Changes

- **Automatic approval per marketplace**, off by default and turned on by an
  administrator.
  - Turning it on records who did it, why, and an expiry of at most 90 days,
    after which it lapses to off until renewed. Ninety days matches the waiver
    limit and the default machine-credential cap; automatic approval keeps its
    own constant, so changing one limit never moves another.
  - It is chosen at registration or later, by an administrator. Under four-eyes
    `enforce` it takes effect only once a second administrator confirms it.
  - It is declarable in the estate.
- **Internal sources** are named by an operator-configured list of URL prefixes,
  for example `https://github.com/acme-internal/`. They are matched on exact
  scheme, host and path segments, and startup refuses a host-wide prefix on a
  multi-tenant host. Hosted marketplaces are internal by construction.
- **An administrator exemption** allows automatic approval for a marketplace
  outside the prefixes. It needs a reason and expires within 90 days. It is set through
  the API or the portal, and never declared in the estate.
- **A snapshot is approved automatically only when all of these hold**;
  otherwise it stays held for a person, exactly as today:
  - it is the marketplace's newest snapshot;
  - it pulls in **no external source at all**;
  - the current vetting chain ran, its effective outcome clears, and every
    required vetter ran rather than being switched off. By default every
    vetter is required; an operator can name the required ones in
    `skills-gateway.approval.auto.required-vetters`, and the others may then be
    switched off without stopping automatic approval. Medium findings are
    allowed. A waived high finding is allowed only if the served version
    already carried it;
  - its executable surface is unchanged from the served version, and every read
    completed. Hooks, hook modules, MCP and LSP servers, tool permissions, and
    any changed file that is not on a short inert list (documentation, images,
    plain data) count as surface;
  - every existing approval gate passes.
- **Dry run.** The deployment setting is `off` (default), `dry-run` or `on`.
  `dry-run` records what would have been approved and publishes nothing.
- **Same evidence as a human approval**, plus what allowed it. The
  automatic approver is a typed system actor on the ledger, and every attempt
  that leaves a snapshot held records why.
- **Re-vetting always enforces for auto-approved snapshots**, whatever the global
  mode. They are revoked on new blocking evidence. Human-approved snapshots keep
  following the global mode. Automatic approval acts only while re-vetting is
  enabled.
- **Bulk withdrawal** of every snapshot approved automatically since a given
  time.
- **New ADR 0023** superseding ADR 0006's deny-only position ("nothing is served
  that a person did not approve") for opted-in marketplaces.
- **Portal:** an Automatic approval card on the marketplace's Settings page.
  It shows whether automatic approval is on, why it is allowed, its grantor,
  reason and expiry, and the active exemption. An administrator turns it on,
  renews it or turns it off there, grants the exemption for a marketplace
  outside the prefixes, and revokes it. The bulk
  withdrawal and the per-snapshot evaluation stay API-only in this change.
- Not **BREAKING**: every new API field is additive, and the defaults keep
  today's behaviour.

New requirements (ids allocated; their text goes into `docs/reqstool/` during
apply). Automatic approval is one capability with many distinct, independently
verified behaviours, so it is decomposed as a parent and dot-numbered children,
as `GW_APPROVAL_0004` and `GW_APPROVAL_0019` are.

- `GW_APPROVAL_0044 — An administrator can opt an internal marketplace into automatic approval of snapshots that need no human judgement` (parent)
  - `GW_APPROVAL_0044.1 — Automatic approval of a marketplace is off unless an administrator turns it on for a limited time`
  - `GW_APPROVAL_0044.2 — Internal sources are those under an operator-configured URL prefix, or hosted`
  - `GW_APPROVAL_0044.3 — Automatic approval of a marketplace outside the internal prefixes needs an expiring administrator exemption`
  - `GW_APPROVAL_0044.4 — Granting automatic approval is subject to the four-eyes rule`
  - `GW_APPROVAL_0044.5 — Only a marketplace's newest held snapshot is approved automatically`
  - `GW_APPROVAL_0044.6 — A snapshot is approved automatically only when it pulls in no external source`
  - `GW_APPROVAL_0044.7 — A snapshot is approved automatically only when the current vetting chain clears and every required vetter ran`
  - `GW_APPROVAL_0044.8 — A snapshot is approved automatically only when its executable surface, read completely, is unchanged from the served baseline`
  - `GW_APPROVAL_0044.9 — An automatic approval passes every approval gate`
  - `GW_APPROVAL_0044.10 — An automatic approval records what a human approval records`
  - `GW_APPROVAL_0044.11 — Every automatic approval, and every attempt that leaves a snapshot held, is on the ledger with its evidence`
  - `GW_APPROVAL_0044.12 — Automatic approval is off, a dry run, or on for a deployment`
  - `GW_APPROVAL_0044.13 — Re-vetting withdraws an automatically approved snapshot on new blocking evidence, whatever the configured mode`
  - `GW_APPROVAL_0044.14 — Every snapshot approved automatically since a given time can be withdrawn in one act`
  - `GW_APPROVAL_0044.15 — An administrator can view and set a marketplace's automatic approval and exemption in the portal`
- `GW_ESTATE_0006 — A marketplace's automatic approval is declarable in the estate`

Each requirement is verified by the SVC that mirrors its id:
`SVC_GW_APPROVAL_0044` and `SVC_GW_APPROVAL_0044.1` to `.15`, and
`SVC_GW_ESTATE_0006`.

The parent is `0044`, not the next free requirement number `0039`, because
`SVC_GW_APPROVAL_0039` to `0043` already verify `GW_APPROVAL_0036` to `0038`.
Starting at `0044` lets every SVC mirror its requirement. Requirement ids `0039`
to `0043` stay unused, as `0023` and `0024` are.

## Capabilities

### New Capabilities

None. Automatic approval is a way of reaching the existing approval decision,
not a capability beside it.

### Modified Capabilities

- `snapshot-approval`: adds `GW_APPROVAL_0044` and its children `.1` to `.15`.
- `declarative-estate`: adds `GW_ESTATE_0006`.

## Impact

- **Backend, `approval` package (no new package):**
  - the automatic approval setting and the internal-source check;
  - the administrator exemption;
  - `AutoApprovalService`;
  - a strict executable-surface reader and comparison;
  - the bulk withdrawal;
  - the approval side effects (ledger, waiver use, webhook) moved out of
    `AdminController` into one `ApprovalRecorder` that both paths call.
- **Elsewhere:**
  - `UpstreamCredentials`' URL-prefix matcher, extracted so it can be shared;
  - `RevetService`, which enforces for auto-approved snapshots and makes the
    release-age retry on its existing tick;
  - `IngestionService` and `WaiverService`, which trigger an attempt.
- **Schema (`V1__init.sql`):** automatic approval columns on `marketplaces`,
  `snapshots.auto_approval_skip_reason`, and an `auto_approval_exemptions` table.
- **REST API (additive):**
  - an optional `autoApproval` field on registration;
  - `GET`/`PUT /api/v1/marketplaces/{name}/auto-approval`, and `DELETE` on
    its exemption;
  - `GET /api/v1/snapshots/{id}/auto-approval`;
  - `POST /api/v1/auto-approval/withdraw`.
- **Portal (`src/main/frontend/src/`):** the Automatic approval card on the
  marketplace Settings page, with its Storybook stories and an e2e test; the
  PR carries before/after screenshots.
- **Configuration:**
  - `skills-gateway.approval.auto.mode` (default `off`);
  - `skills-gateway.approval.auto.internal-prefixes`;
  - `skills-gateway.approval.auto.required-vetters` (default: unset, meaning
    every vetter);
  - `skills-gateway.estate.marketplaces[].auto-approval.{enabled, reason, expires-at}`.
- **Docs:**
  - the new ADR 0023, and amendments to ADR 0006 and ADR 0021;
  - the approving, registering, estate and configuration pages;
  - trust boundaries;
  - the capability map.

### Why the capability map cannot simply absorb this

It mostly can, and the change is shaped to let it. The Approval row gains
"or, for an opted-in internal marketplace, a recorded automatic decision", and
nothing else in the map changes:

- **No new backend package, grantable role, or scheduled sweep.** The release-age
  retry rides the existing re-vetting tick.
- **It adds one Spring test context.** `ContextBudgetTests` rises to 28 for
  `AutoApprovalTests`. The attempt acts only while re-vetting is enabled
  (design D12), and the shared context disables re-vetting so no sweep reaches
  other suites' fixtures. Proving that ingestion publishes with no person
  therefore needs a second gateway, as `FourEyesEnforceTests` needs one for
  `enforce`. Building the service by hand inside the shared context was
  rejected: it would leave the triggers untested in `on` mode.
- **It adds six settable names.** `ConfigSurfaceBudgetTests` counts three of
  them and rises to 112; the three estate keys sit inside the estate marketplace
  list's element, which its walk does not descend into. All six are argued:
  - **The mode:** `off` is the incident switch, and `dry-run` is the adopter's
    measurement.
  - **The prefix list:** "internal" has to be the operator's statement, not a
    marketplace's claim about itself.
  - **The required vetters:** which checks must have run before content is
    published unattended is the operator's call, not that of whoever turns
    automatic approval on. Unset keeps the strictest rule, so the leaf only ever loosens on a
    stated choice. It is gateway-wide rather than per marketplace, so a grantor
    cannot lower the bar for the one marketplace they are granting.
  - **The three estate keys:** the declarative-estate obligation, with the
    reason and expiry that make declared automatic approval a dated, recertified decision
    rather than a standing bit.
- **It adds one object that is not declarable in the estate, the exemption.**
  Like a waiver, it is an expiring exception that a named person renews, so it
  is set through the API or the portal and deliberately left out of the estate.
