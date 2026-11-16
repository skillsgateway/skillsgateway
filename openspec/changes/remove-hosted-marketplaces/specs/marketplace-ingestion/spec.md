# marketplace-ingestion — delta for remove-hosted-marketplaces

## ADDED Requirements

### Requirement: GW_AUTH_0090
The system SHALL implement GW_AUTH_0090.

#### Scenario: SVC_GW_AUTH_0110
- **WHEN** SVC_GW_AUTH_0110 is run
- **THEN** it passes

#### Scenario: SVC_GW_AUTH_0111
- **WHEN** SVC_GW_AUTH_0111 is run
- **THEN** it passes

## MODIFIED Requirements

### Requirement: GW_INGEST_0035
The system SHALL implement GW_INGEST_0035.

#### Scenario: SVC_GW_INGEST_0035
- **WHEN** SVC_GW_INGEST_0035 is run
- **THEN** it passes

### Requirement: GW_INGEST_0066
The system SHALL implement GW_INGEST_0066.

#### Scenario: SVC_GW_INGEST_0066
- **WHEN** SVC_GW_INGEST_0066 is run
- **THEN** it passes

#### Scenario: SVC_GW_INGEST_0066.1
- **WHEN** SVC_GW_INGEST_0066.1 is run
- **THEN** it passes

### Requirement: GW_INGEST_0070
The system SHALL implement GW_INGEST_0070.

#### Scenario: SVC_GW_INGEST_0070
- **WHEN** SVC_GW_INGEST_0070 is run
- **THEN** it passes

## REMOVED Requirements

### Requirement: GW_FACADE_0006

**Reason**: The gateway no longer hosts marketplaces. An organisation that authors its own skills keeps them on a forge and registers that repository as an upstream (ADR 0024, superseding ADR 0007). Owner-approved removal of the feature; its verification case SVC_GW_FACADE_0006 is removed with it, because the behaviour it covered no longer exists.

**Migration**: Register the forge repository as an upstream. An operator whose deployment holds a live hosted marketplace follows the export-and-re-home procedure; the schema upgrade refuses to run until none is live (GW_AUTH_0090).

### Requirement: GW_INGEST_0017

**Reason**: Content no longer reaches quarantine from a push. Owner-approved removal; its verification case SVC_GW_INGEST_0017 is removed with it. The pipeline it said a push must traverse is unchanged and still verified for fetched content.

**Migration**: Content reaches quarantine only by being fetched from an upstream.
