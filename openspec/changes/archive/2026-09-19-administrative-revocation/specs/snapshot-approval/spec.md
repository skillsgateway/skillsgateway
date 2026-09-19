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

WHEN a snapshot an administrator revoked is approved ordinarily
THEN the approval SHALL be refused naming the revocation with its stated reason,
acting identity and time, the snapshot SHALL remain unapproved, and nothing
SHALL be published for it.

WHEN the refusal happens and the vetting chain would currently clear the
snapshot
THEN the approval SHALL still be refused, since there is no finding to waive and
only a person can contradict the person who withdrew it.

WHEN an administrator reverses the revocation on a stated, non-empty reason and
is not the identity that revoked it
THEN the approval SHALL proceed subject to every other approval gate unchanged,
the reversal SHALL be appended to the append-only audit ledger naming the
revocation it reverses, and the snapshot SHALL be marked so that content served
over a reversed administrative revocation is distinguishable from content no
administrator ever withdrew.

WHEN the identity that revoked the snapshot attempts to reverse it
THEN the reversal SHALL be refused.

WHEN a reversal states no reason or an empty reason, or is requested by a caller
who is not an administrator
THEN it SHALL be refused.

WHEN a snapshot was revoked by re-vetting rather than by an administrator
THEN it SHALL be approved again by clearing the finding that caused it, exactly
as before, and SHALL NOT require a reversal.
