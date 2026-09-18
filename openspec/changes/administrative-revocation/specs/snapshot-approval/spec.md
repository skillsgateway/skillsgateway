# snapshot-approval — delta for administrative-revocation

## ADDED Requirements

### Requirement: GW_APPROVAL_0015
The system SHALL implement GW_APPROVAL_0015.

#### Scenario: SVC_GW_APPROVAL_0015
The system SHALL pass SVC_GW_APPROVAL_0015.

WHEN an administrator revokes an approved snapshot with a non-empty stated
reason
THEN the snapshot SHALL be recorded revoked with that reason, the acting
identity and the time,
AND its content SHALL stop being served through the facade,
AND the revocation SHALL be appended to the append-only ledger and announced as
a lifecycle event.

WHEN the caller is not an administrator, or states no reason, or states an empty
reason
THEN the revocation SHALL be refused and nothing SHALL change.

WHEN the vetting chain would currently clear the snapshot
THEN the revocation SHALL still take effect, since the administrator's reason is
knowledge the chain does not hold.

WHEN the re-vetting mode is the one that only records findings rather than
acting on them
THEN the revocation SHALL still take effect, since that mode governs automated
re-vetting and not an administrator's decision.

WHEN the snapshot is not approved
THEN the revocation SHALL be refused.

### Requirement: GW_APPROVAL_0016
The system SHALL implement GW_APPROVAL_0016.

#### Scenario: SVC_GW_APPROVAL_0016
The system SHALL pass SVC_GW_APPROVAL_0016.

WHEN a revocation request does not state what the marketplace serves afterwards
THEN it SHALL be refused before anything is revoked.

WHEN the request states that the marketplace serves nothing afterwards
THEN the marketplace SHALL serve nothing, and that outcome SHALL be recorded on
the ledger.

WHEN the request states that the marketplace rolls back, and an earlier approved
snapshot of that marketplace exists
THEN that snapshot SHALL be served, and the rollback SHALL be recorded on the
ledger as a publication distinct from the revocation.

WHEN the request states that the marketplace rolls back and no earlier approved
snapshot of that marketplace exists
THEN the request SHALL be refused before anything is revoked, rather than
serving nothing instead.

WHEN the revoked snapshot is not the one the marketplace is serving
THEN what the marketplace serves SHALL be unchanged.

### Requirement: GW_APPROVAL_0017
The system SHALL implement GW_APPROVAL_0017.

#### Scenario: SVC_GW_APPROVAL_0017
The system SHALL pass SVC_GW_APPROVAL_0017.

WHEN an approval is requested for a snapshot whose marketplace and commit were
previously revoked
THEN the approval SHALL be refused, the snapshot SHALL remain unapproved, and
nothing SHALL be published.

WHEN such an approval is refused
THEN the refusal SHALL name the revocation that caused it, carrying its stated
reason, the identity that made it and when.
