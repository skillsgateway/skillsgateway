# snapshot-retention Specification

## Purpose
Keeping storage and the ledger bounded without losing what must be kept: retention policy evaluation, soft delete with a restore window and scheduled compaction, the portal deletion surface, and the guarantees that approved snapshots, administrative revocations and administrative ledger entries are never removed, and that ledger reads are trimmed only behind every export sink's position, keeping each identity's latest pack send per marketplace.
## Requirements
### Requirement: GW_RETENTION_0001
The system SHALL implement GW_RETENTION_0001.

#### Scenario: SVC_GW_RETENTION_0001
- **WHEN** SVC_GW_RETENTION_0001 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0002
The system SHALL implement GW_RETENTION_0002.

#### Scenario: SVC_GW_RETENTION_0002
- **WHEN** SVC_GW_RETENTION_0002 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0003
The system SHALL implement GW_RETENTION_0003.

#### Scenario: SVC_GW_RETENTION_0003
- **WHEN** SVC_GW_RETENTION_0003 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0004
The system SHALL implement GW_RETENTION_0004.

#### Scenario: SVC_GW_RETENTION_0004
- **WHEN** SVC_GW_RETENTION_0004 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0005
The system SHALL implement GW_RETENTION_0005.

#### Scenario: SVC_GW_RETENTION_0005
- **WHEN** SVC_GW_RETENTION_0005 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0006
The system SHALL implement GW_RETENTION_0006.

#### Scenario: SVC_GW_RETENTION_0006
- **WHEN** SVC_GW_RETENTION_0006 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0007
The system SHALL implement GW_RETENTION_0007.

#### Scenario: SVC_GW_RETENTION_0007
- **WHEN** SVC_GW_RETENTION_0007 is run
- **THEN** it passes

### Requirement: GW_FACADE_0019
The system SHALL implement GW_FACADE_0019.

#### Scenario: SVC_GW_FACADE_0019
- **WHEN** SVC_GW_FACADE_0019 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0008
The system SHALL implement GW_RETENTION_0008.

#### Scenario: SVC_GW_RETENTION_0008
- **WHEN** SVC_GW_RETENTION_0008 is run
- **THEN** it passes

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

### Requirement: GW_RETENTION_0009
The system SHALL implement GW_RETENTION_0009.

#### Scenario: SVC_GW_RETENTION_0009
- **WHEN** SVC_GW_RETENTION_0009 is run
- **THEN** it passes


### Requirement: GW_RETENTION_0010
The system SHALL implement GW_RETENTION_0010.

#### Scenario: SVC_GW_RETENTION_0010
- **WHEN** SVC_GW_RETENTION_0010 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0011
The system SHALL implement GW_RETENTION_0011.

#### Scenario: SVC_GW_RETENTION_0011
- **WHEN** SVC_GW_RETENTION_0011 is run
- **THEN** it passes

### Requirement: GW_RETENTION_0012
The system SHALL implement GW_RETENTION_0012.

#### Scenario: SVC_GW_RETENTION_0012
- **WHEN** SVC_GW_RETENTION_0012 is run
- **THEN** it passes
