# audit-export — delta for bound-ledger-growth

## MODIFIED Requirements

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
