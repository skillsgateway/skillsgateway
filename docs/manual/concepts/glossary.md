# Glossary

Terms as this project uses them. Several are ordinary git or security words with
a narrower meaning here.

**Approval**
:   The single act that publishes content. `ApprovalService` fetches a pinned
    quarantine ref into the published repository and force-updates
    `refs/heads/main` to that SHA. It is the only code path that makes anything
    reachable by a client.

**Audit ledger** (or just *ledger*)
:   The append-only `fetch_log` table recording every facade fetch and every
    administrative action. No code path updates or deletes it.

**Chain run**
:   One execution of the vetting chain against one snapshot, recorded with its
    verdicts, its findings, and a fail-closed `clear` or `blocked` outcome.

**Connector**
:   A pluggable vetting component. It is handed a snapshot's identity and a
    read-only walk over its files, and answers with a verdict. The gateway
    orchestrates connectors; it does not vet content itself.

**Finding**
:   One thing a connector found: a stable rule id, a severity, a `path:line`
    location, and a message. The rule id is the identity a future waiver would
    be written against.

**Override**
:   Approving a snapshot whose chain run blocked, with a mandatory reason. The
    reason is recorded against the run and in the ledger. It is blanket and
    non-expiring — the minimum auditable escape hatch, not a waiver.

**Verdict**
:   A connector's conclusion about a snapshot: `PASS`, `WARN`, `FAIL`, `ERROR`
    or `PENDING`. Only `PASS` and `WARN` let a chain run clear.

**BFF** (backend for frontend)
:   The pattern the portal uses: the application holds the OIDC tokens and the
    browser holds only a session cookie.

**Facade**
:   The read-only git smart-HTTP server at `/git/**`. The only surface end users
    touch. Authenticated by PAT only; writes impossible by construction.

**Held**
:   The state every snapshot starts in. Stored, inspectable, and serving
    nothing.

**Revoked**
:   A snapshot that was approved and published, and that a later re-vetting run
    retroactively quarantined. Its published refs are gone; its quarantined copy
    is not. It returns to being served only through a fresh approve decision.
    See [Re-vetting approved content](../guides/re-vetting.md).

**Ingestion**
:   Cloning an upstream default branch into quarantine and pinning the tip
    commit as `refs/snapshots/{sha}`. Explicit — there is no upstream watcher.

**Marketplace**
:   A registered upstream git repository. In the Claude Code sense, a repository
    containing `.claude-plugin/marketplace.json`. Its name is its identity in
    the portal, the API and the facade URL.

**PAT** (personal access token)
:   The credential git clients authenticate with — `sgw_` plus 32 random bytes,
    stored only as a SHA-256 digest, shown exactly once.

**Provenance**
:   The record of where a snapshot came from and who decided on it: marketplace,
    upstream URL, upstream SHA, state, ingestion time, deciding principal and
    timestamp.

**Published repository**
:   `{data-dir}/published/{marketplace}.git`. Holds exactly one served ref,
    `refs/heads/main`. Created only by approval, read only by the facade.

**Quarantine repository**
:   `{data-dir}/quarantine/{marketplace}.git`. Holds one immutable
    `refs/snapshots/{sha}` per ingested commit. **Never served.**

**Skill**
:   A unit of agent capability — a `SKILL.md` directory in the open Agent Skills
    format, or a component of a Claude Code plugin.

**Snapshot**
:   One upstream commit captured at one moment, with a vetting decision
    attached. The unit of review, approval, serving and audit.

**Sync mode**
:   How new upstream content reaches quarantine for one marketplace:
    `on-demand` (an operator's explicit call, the default), `scheduled` (the
    gateway's polling sweep), or `webhook` (a signed forge push webhook). Only
    the trigger — every mode lands snapshots `held` behind the same approval
    gate.

**Upload-pack**
:   The git smart-HTTP operation that serves objects to a fetching client. The
    only git service the facade enables; receive-pack (push) is disabled.

**Violation**
:   The reason ingestion flagged a snapshot — for example an external plugin
    source, which is rejected fail-closed in the current scope.
