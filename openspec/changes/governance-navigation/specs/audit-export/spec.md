# audit-export — delta for governance-navigation

## MODIFIED Requirements

### Requirement: GW_AUDIT_0006
The system SHALL implement GW_AUDIT_0006.

The portal audit surface presents the ledger as a table whose rows carry a status
derived from the entry's event and, for a completed-vetting entry, the outcome in
its detail: a blocked verdict is drawn in the portal's destructive colour (the
same treatment the marketplace surfaces use), a clear verdict in the accent, a
warn verdict muted. The marketplace column links to that marketplace's detail
page, and the table sorts and filters per column and paginates. The NDJSON export
and its resumable cursor are unchanged.

The ledger is the audit page's content, and the export download is an action in
the page's header. The registered export sinks are listed on the portal's
integrations surface, beside the webhook subscribers, not on the audit page.

#### Scenario: SVC_GW_AUDIT_0006
The system SHALL pass SVC_GW_AUDIT_0006.
