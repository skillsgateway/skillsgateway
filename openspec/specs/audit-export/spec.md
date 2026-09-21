# audit-export Specification

## Purpose
TBD - created by archiving change add-audit-ledger-export. Update Purpose after archive.
## Requirements
### Requirement: GW_AUDIT_0003
The system SHALL implement GW_AUDIT_0003.

#### Scenario: SVC_GW_AUDIT_0003
The system SHALL pass SVC_GW_AUDIT_0003.

### Requirement: GW_AUDIT_0004
The system SHALL implement GW_AUDIT_0004.

#### Scenario: SVC_GW_AUDIT_0004
The system SHALL pass SVC_GW_AUDIT_0004.

### Requirement: GW_AUDIT_0005
The system SHALL implement GW_AUDIT_0005.

Replay is bounded by what the ledger still holds. Trimming removes only entries
every enabled sink has already taken (GW_RETENTION_0009), so a replay to an
existing sink can only ask again for what that sink was already handed; a sink
registered later and starting at position zero receives the ledger from the
oldest surviving entry, not from the beginning of time. The requirement's
ID-level statement and its verification are unchanged; this records the bound
that ledger retention puts on the cursor's reach.

#### Scenario: SVC_GW_AUDIT_0005
The system SHALL pass SVC_GW_AUDIT_0005.

### Requirement: GW_AUDIT_0006
The system SHALL implement GW_AUDIT_0006.

The portal audit surface presents the ledger as a table whose rows carry a status
derived from the entry's event and, for a completed-vetting entry, the outcome in
its detail: a blocked verdict is drawn in the portal's destructive colour (the
same treatment the marketplace surfaces use), a clear verdict in the accent, a
warn verdict muted. The marketplace column links to that marketplace's detail
page, and the table sorts and filters per column and paginates. The NDJSON export
and its resumable cursor are unchanged. This is a presentation and navigation
change over the same ledger; the requirement's ID-level statement and its
verification are unchanged.

#### Scenario: SVC_GW_AUDIT_0006
The system SHALL pass SVC_GW_AUDIT_0006.

### Requirement: GW_AUDIT_0008
The system SHALL implement GW_AUDIT_0008.

#### Scenario: SVC_GW_AUDIT_0008
The system SHALL pass SVC_GW_AUDIT_0008.

