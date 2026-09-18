# marketplace-ingestion — delta for administrative-revocation

## ADDED Requirements

### Requirement: GW_INGEST_0032
The system SHALL implement GW_INGEST_0032.

#### Scenario: SVC_GW_INGEST_0032
The system SHALL pass SVC_GW_INGEST_0032.

WHEN ingestion resolves an upstream commit whose marketplace and commit were
previously revoked
THEN no snapshot SHALL be placed in the held state for it, and the refusal SHALL
be recorded on the append-only ledger naming the revocation, its stated reason
and the identity that made it.

WHEN that ingestion was triggered by the scheduled sweep rather than by a person
THEN the refusal SHALL be recorded in the same way, so that a marketplace whose
upstream has not moved past the revoked commit does not silently reappear for
approval.

WHEN ingestion resolves an upstream commit that has not been revoked
THEN ingestion SHALL proceed unchanged.
