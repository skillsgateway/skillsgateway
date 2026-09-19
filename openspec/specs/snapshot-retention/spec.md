# snapshot-retention Specification

## Purpose
TBD - created by archiving change add-snapshot-retention. Update Purpose after archive.
## Requirements
### Requirement: GW_RETENTION_0001
The system SHALL implement GW_RETENTION_0001.

#### Scenario: SVC_GW_RETENTION_0001
The system SHALL pass SVC_GW_RETENTION_0001.

### Requirement: GW_RETENTION_0002
The system SHALL implement GW_RETENTION_0002.

#### Scenario: SVC_GW_RETENTION_0002
The system SHALL pass SVC_GW_RETENTION_0002.

### Requirement: GW_RETENTION_0003
The system SHALL implement GW_RETENTION_0003.

#### Scenario: SVC_GW_RETENTION_0003
The system SHALL pass SVC_GW_RETENTION_0003.

### Requirement: GW_RETENTION_0004
The system SHALL implement GW_RETENTION_0004.

#### Scenario: SVC_GW_RETENTION_0004
The system SHALL pass SVC_GW_RETENTION_0004.

### Requirement: GW_RETENTION_0005
The system SHALL implement GW_RETENTION_0005.

#### Scenario: SVC_GW_RETENTION_0005
The system SHALL pass SVC_GW_RETENTION_0005.

### Requirement: GW_RETENTION_0006
The system SHALL implement GW_RETENTION_0006.

#### Scenario: SVC_GW_RETENTION_0006
The system SHALL pass SVC_GW_RETENTION_0006.

### Requirement: GW_RETENTION_0007
The system SHALL implement GW_RETENTION_0007.

#### Scenario: SVC_GW_RETENTION_0007
The system SHALL pass SVC_GW_RETENTION_0007.

### Requirement: GW_FACADE_0019
The system SHALL implement GW_FACADE_0019.

#### Scenario: SVC_GW_FACADE_0019
The system SHALL pass SVC_GW_FACADE_0019.

### Requirement: GW_RETENTION_0008
The system SHALL implement GW_RETENTION_0008.

#### Scenario: SVC_GW_RETENTION_0008
The system SHALL pass SVC_GW_RETENTION_0008.

WHEN retention reclaims the content of a snapshot an administrator revoked
THEN the content SHALL be reclaimed as for any other eligible snapshot.

WHEN retention would permanently remove the record of a snapshot an
administrator revoked, whether selected by policy or requested by hand
THEN the removal SHALL be refused, the record SHALL survive, and the refusal
SHALL state that it is retained because the snapshot was administratively
revoked.

WHEN the same upstream commit is ingested again after its content was reclaimed
THEN the revocation SHALL still be found, so the approval gate still refuses it.

WHEN retention would permanently remove the record of a snapshot revoked by
re-vetting rather than by an administrator
THEN the removal SHALL proceed as before.

