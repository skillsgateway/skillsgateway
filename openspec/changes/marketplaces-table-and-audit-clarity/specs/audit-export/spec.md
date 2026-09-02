# audit-export Specification (delta)

## MODIFIED Requirements

### Requirement: GW_0030
The system SHALL implement GW_0030.

The portal audit surface presents the ledger as a table whose rows carry a status
derived from the entry's event and, for a completed-vetting entry, the outcome in
its detail: a blocked verdict is drawn in the portal's destructive colour (the
same treatment the marketplace surfaces use), a clear verdict in the accent, a
warn verdict muted. The marketplace column links to that marketplace's detail
page, and the table sorts and filters per column and paginates. The NDJSON export
and its resumable cursor are unchanged. This is a presentation and navigation
change over the same ledger; the requirement's ID-level statement and its
verification are unchanged.

#### Scenario: SVC_GW_0030
The system SHALL pass SVC_GW_0030.
