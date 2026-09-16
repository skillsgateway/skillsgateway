# Glossary

Terms as this project uses them, in alphabetical order. Several are ordinary git
or security words with a narrower meaning here. Each entry is a definition and a
pointer; the page it points at is where the mechanism is explained.

**Adoption report**
:   A read-only aggregation over the fetch ledger: per marketplace, who fetched
    what, how recently, and which SHAs are still the served tip. See
    [Adoption](../reference/api/adoption.md).

**Approval**
:   The single act that publishes content, and the only code path that makes
    anything reachable by a client. See
    [Approving and rejecting snapshots](../guides/approving-snapshots.md).

**Audit ledger** (or just *ledger*)
:   The append-only record of every facade fetch and every administrative
    action. No code path updates or deletes it. See
    [Snapshots and the audit ledger](snapshots-and-ledger.md).

**Audit sink**
:   A registered HTTP endpoint the gateway pushes ledger entries to, advancing
    its own cursor so delivery is at-least-once rather than best-effort. See
    [Exporting the audit ledger](../guides/exporting-the-audit-ledger.md#push-sinks).

**BFF** (backend for frontend)
:   The pattern the portal uses: the application holds the OIDC tokens and the
    browser holds only a session cookie. See
    [Trust boundaries](trust-boundaries.md).

**Blast radius**
:   Who already fetched a snapshot, and therefore who is affected if it is
    retracted. `warn` mode measures it before `enforce` is granted the power to
    cause it. See
    [Re-vetting approved content](../guides/re-vetting.md#warn-first-then-enforce).

**Chain identity**
:   The identity and version of every vetter that produced a chain run,
    stamped into the run. It is what makes "the same content, re-vetted by a
    changed chain" a distinguishable event. See
    [Vetting — the vetter chain](vetting.md).

**Chain run**
:   One execution of the vetting chain against one snapshot, recorded with its
    verdicts and its findings. The record is raw evidence and is never
    rewritten. See [Vetting — the vetter chain](vetting.md).

**Closure**
:   The set of external plugin sources one composite snapshot serves, recorded
    with the snapshot as immutable copies of what each source was declared as
    and what it resolved to. See
    [the closure record](snapshots-and-ledger.md#the-closure-record).

**Compaction**
:   The retention pass that irreversibly removes what an earlier evaluation
    marked for deletion, reclaiming the objects behind it. See
    [Snapshot retention](../reference/retention.md#the-two-passes).

**Connector**
:   The transport that lets a vetter running outside the gateway take part: the
    HTTP trigger and callback contract configured under
    `skills-gateway.vetting.external[*]`. Every external connector contributes
    one vetter; a built-in vetter has no connector. The word means nothing
    wider in this project — integrations to other systems are subscribers, sinks
    or mirrors. See
    [External connectors](vetting.md#external-connectors).

**Effective outcome**
:   What actually gates an approval: the recorded chain run with the waivers
    active at that instant layered over it, computed on every read.
    `CLEAR_WITH_WAIVERS` is deliberately a different word from `CLEAR`. See
    [The effective outcome](vetting.md#the-effective-outcome).

**Estate**
:   Everything the gateway governs that is created at runtime rather than coded:
    marketplaces, role grants, webhook subscribers, audit sinks and policy
    rules. It can be managed through the API, declared in configuration
    (`skills-gateway.estate.*`), or both. See
    [Declarative estate configuration](../guides/declarative-estate.md).

**Estate reconciliation**
:   Converging the running estate to the declared one, at startup and on demand.
    See [Declarative estate configuration](../guides/declarative-estate.md).

**External plugin source**
:   A plugin the marketplace manifest points at outside its own repository. The
    gateway admits nothing by default; when configured, an admitted source is
    resolved into the snapshot rather than dereferenced at install time. See
    [external plugin sources](../reference/configuration.md#ingestion-external-plugin-sources).

**Facade**
:   The read-only git smart-HTTP server at `/git/**`, the only surface end users
    touch. Authenticated by PAT only; writes impossible by construction. See
    [Git smart-HTTP facade](../reference/git-facade.md).

**Finding**
:   One thing a vetter found: a stable rule id, a severity, a `path:line`
    location, and a message. The rule id is the identity a waiver is written
    against. See
    [The vetter contract](vetting.md#the-vetter-contract).

**Forge mirror**
:   An optional outbound copy of approved refs pushed to a repository on an
    external code host, for browsing only. It is off by default, and a fetch
    from it is on no ledger. See
    [The read-only forge mirror](../guides/read-only-forge-mirror.md).

**Four-eyes** (separation of duties)
:   The rule that a reviewer may not approve a snapshot they themselves
    supplied — by registering the marketplace, triggering the ingestion, or
    authoring a waiver the approval leans on. It records by default and enforces
    on opt-in. See
    [Separation of duties](../reference/configuration.md#separation-of-duties-four-eyes).

**Held**
:   The state every snapshot starts in. Stored, inspectable, and serving
    nothing. See [Lifecycle — quarantine to serve](lifecycle.md).

**Hosted marketplace**
:   A marketplace whose content is pushed straight to the gateway rather than
    fetched, so it has **no upstream** and takes no clone URL. Everything after
    the push is unchanged: quarantined, vetted, held, and served only once
    somebody approves it. See
    [Publishing first-party skills](../guides/publishing-first-party-skills.md).

**Ingestion**
:   Cloning an upstream default branch into quarantine and pinning the tip
    commit as `refs/snapshots/{sha}`. Explicit by default; see *Sync mode* for
    the other triggers. See
    [Syncing from upstream automatically](../guides/upstream-sync.md).

**Lease**
:   A conditional database row one scheduled background pass takes before it
    runs, so a pass runs on one replica per interval rather than once per
    replica. It is never released early and lapses on its own. See
    [Running more than one replica](../guides/storage-backends.md#running-more-than-one-replica).

**Machine API credential**
:   An access token whose API scope list is non-empty — what a Terraform
    provider or a CI job holds. Not a second credential type: the same
    issue-rotate-revoke-expire lifecycle as every other token. See
    [Machine API credentials](../reference/api/tokens.md#machine-api-credentials).

**Marketplace**
:   A registered upstream git repository — in the Claude Code sense, one
    containing `.claude-plugin/marketplace.json` — or a hosted marketplace,
    which has no upstream at all. Its name is its identity in the portal, the
    API and the facade URL. See
    [Registering a marketplace](../guides/registering-a-marketplace.md).

**Minimum release age**
:   A cooling-off window: a snapshot ingested less than the configured time ago
    cannot be approved however clear its verdicts are. Checked at the approval
    request, and deliberately not a vetter. See
    [Minimum release age](../reference/configuration.md#minimum-release-age).

**Override**
:   A per-approval act, reserved to an administrator, that approves one snapshot
    whose effective vetting outcome is blocked. A reason is mandatory; it lifts
    **only** the vetting gate — policy, minimum release age and four-eyes still
    decide — and it writes the distinct ledger event
    `snapshot-approved-over-vetting-failure`. See
    [Administrative override of a blocked outcome](../reference/api/marketplaces.md#administrative-override-of-a-blocked-outcome).

**PAT** (personal access token)
:   The credential git clients authenticate with — `sgw_` plus 32 random bytes,
    stored only as a SHA-256 digest, shown exactly once. The same credential is
    the machine API credential when it carries API scopes. See
    [Access tokens](../reference/api/tokens.md).

**Policy rule**
:   A named CEL deny expression over a snapshot's facts, evaluated fail-closed
    at approval time; there is no per-snapshot override. See
    [Policy deny rules](../guides/policy-rules.md).

**Provenance**
:   The record of where a snapshot came from and who decided on it: marketplace,
    upstream URL, upstream SHA, state, ingestion time, deciding principal and
    timestamp. See
    [Snapshots and the audit ledger](snapshots-and-ledger.md).

**Published repository**
:   `{data-dir}/published/{marketplace}.git`. Holds exactly one served ref,
    `refs/heads/main`. Created only by approval, read only by the facade. See
    [Lifecycle — quarantine to serve](lifecycle.md).

**Quarantine repository**
:   `{data-dir}/quarantine/{marketplace}.git`. Holds one immutable
    `refs/snapshots/{sha}` per ingested commit. **Never served.** See
    [Lifecycle — quarantine to serve](lifecycle.md).

**Re-vetting**
:   Running the vetting chain again over snapshots that are already approved and
    being served, on a schedule or on demand, and deciding what a fresh answer
    means for content teams already depend on. See
    [Re-vetting approved content](../guides/re-vetting.md).

**Revoked**
:   A snapshot that was approved and published, and that a later re-vetting run
    retroactively quarantined. Its published refs are gone; its quarantined copy
    is not. It returns to being served only through a fresh approve decision.
    See [Re-vetting approved content](../guides/re-vetting.md).

**Role grant**
:   One row giving a principal a role — `admin`, `approver` (scoped to one
    marketplace) or `auditor`. Grant rows are one of three sources of roles,
    alongside the `skills-gateway.roles.admins` list and the identity provider's
    claim mappings; a session's effective roles are their **union**. See
    [Groups instead of grants](../guides/identity-providers.md#groups-instead-of-grants).

**Rotation**
:   Replacing a token's secret while keeping its grant — name, scopes and the
    same expiry deadline. The old token is revoked before the new one is issued.
    See [Access tokens](../reference/api/tokens.md).

**Rule id**
:   The stable identifier of one vetter rule (`aws-access-key-id`), carried
    by every finding it raises and the identity a waiver names. It is part of
    the vetter contract rather than a display string, and is unrelated to a
    *policy rule*. See
    [The vetter contract](vetting.md#the-vetter-contract).

**Scope** (of a token)
:   Three separate dimensions with three different empty values: `scopes`
    (fetch through the facade), `pushScopes` (publish) and `apiScopes` (the
    control plane). See
    [Three scope dimensions](../reference/api/tokens.md#three-scope-dimensions-three-different-empty-values).

**Skill**
:   A unit of agent capability — a `SKILL.md` directory in the open Agent Skills
    format, or a component of a Claude Code plugin. See
    [Consuming approved skills](../guides/consuming-skills.md).

**Snapshot**
:   One commit captured at one moment, with a vetting decision attached — the
    unit of review, approval, serving and audit. It may be a composite commit
    the gateway synthesised from the upstream tree plus resolved external plugin
    sources, with the upstream commit as its parent. See
    [Composite snapshots](snapshots-and-ledger.md#composite-snapshots).

**Suppression**
:   What an active waiver does to a finding: it removes it from the inputs the
    effective outcome is derived from, so the verdict is recomputed without it.
    The recorded run still shows the finding. See
    [The effective outcome](vetting.md#the-effective-outcome).

**Sync mode**
:   How new upstream content reaches quarantine for one marketplace:
    `on-demand` (the default), `scheduled` or `webhook`. Only the trigger —
    every mode lands snapshots `held` behind the same approval gate. See
    [Syncing from upstream automatically](../guides/upstream-sync.md).

**Upload-pack**
:   The git smart-HTTP operation that serves objects to a fetching client. The
    only git service the facade enables. See
    [Git smart-HTTP facade](../reference/git-facade.md).

**Verdict**
:   A vetter's conclusion about a snapshot: `PASS`, `WARN`, `FAIL`, `ERROR`,
    `PENDING` or `DISABLED`. Only `PASS` and `WARN` clear, and `DISABLED` is the
    one state that neither clears nor blocks. See
    [Verdict states](vetting.md#verdict-states).

**Vetter**
:   The element of the vetting chain: whatever examines a snapshot and answers
    with a verdict and findings. Built in — `secret-scan`, `prompt-injection`,
    `license-scan`, `skill-conformance` — or external, reached over a
    connector. It is handed the snapshot's identity and a
    read-only walk over its files, and nothing else. An administrator can switch
    one off globally or for one marketplace — an audited act that records a
    `DISABLED` verdict rather than shortening the chain
    ([vetter settings](../reference/api/marketplaces.md#vetter-enabledisable)).
    The industry equivalents are Harbor's scanner adapter and
    Dependency-Track's analyzer. See
    [Vetting — the vetter chain](vetting.md) and, for adding one of your own,
    [Adding an external vetter](../guides/adding-an-external-vetter.md).

**Violation**
:   The reason ingestion flagged a snapshot: an external plugin source that
    admission refused, that could not be resolved, or that exceeded a resolution
    budget. External sources are admissible where they are configured to be, so
    a violation records a refusal rather than their mere presence. See
    [Violations](../guides/approving-snapshots.md#violations).

**Virtual catalog**
:   The synthesized repository at `/git/catalog` vendoring the currently served
    snapshot of every marketplace under one merged manifest, rebuilt on every
    approval and revocation. See
    [The virtual catalog](../guides/virtual-catalog.md).

**Waiver**
:   An accepted-risk exception for one finding rule, on one marketplace, within
    one scope, until one date. All four are mandatory, and so are a
    justification and the identity accepting the risk; there is no unlimited
    waiver. See [Waiving a vetting finding](../guides/waiving-findings.md).

**Webhook subscriber**
:   A registered endpoint the gateway delivers signed lifecycle events to, with
    its own retry budget and backoff. See
    [Receiving lifecycle webhooks](../guides/lifecycle-webhooks.md).
