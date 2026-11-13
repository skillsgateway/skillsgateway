# lifecycle-webhooks Specification

## Purpose
Outbound notification of snapshot and marketplace lifecycle events: signed deliveries to subscribers that filter for them, retried with backoff, from a served registry of namespaced event names; an approval-pending event that announces without disclosing; a delivery contract published in the API document and guarded against breaking change; and the portal page for administering subscribers and watching deliveries.
## Requirements
### Requirement: GW_WEBHOOK_0001
The system SHALL implement GW_WEBHOOK_0001.

#### Scenario: SVC_GW_WEBHOOK_0001
- **WHEN** SVC_GW_WEBHOOK_0001 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0002
The system SHALL implement GW_WEBHOOK_0002.

#### Scenario: SVC_GW_WEBHOOK_0002
- **WHEN** SVC_GW_WEBHOOK_0002 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0003
The system SHALL implement GW_WEBHOOK_0003.

#### Scenario: SVC_GW_WEBHOOK_0003
- **WHEN** SVC_GW_WEBHOOK_0003 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0004
The system SHALL implement GW_WEBHOOK_0004.

The subscribers the portal lists are the lifecycle subscribers. A subscriber that
is an audit sink's delivery channel is listed with its sink on the audit sinks
surface, not among them, and its delivery attempts remain listed with the others,
marked as the sink's and linking to where the sink is managed.

#### Scenario: SVC_GW_WEBHOOK_0004
- **WHEN** SVC_GW_WEBHOOK_0004 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0005
The system SHALL implement GW_WEBHOOK_0005.

#### Scenario: SVC_GW_WEBHOOK_0005
- **WHEN** SVC_GW_WEBHOOK_0005 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0006
The system SHALL implement GW_WEBHOOK_0006.

#### Scenario: SVC_GW_WEBHOOK_0006
- **WHEN** SVC_GW_WEBHOOK_0006 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0007
The system SHALL implement GW_WEBHOOK_0007.

#### Scenario: SVC_GW_WEBHOOK_0007
- **WHEN** SVC_GW_WEBHOOK_0007 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0008
The system SHALL implement GW_WEBHOOK_0008.

#### Scenario: SVC_GW_WEBHOOK_0008
- **WHEN** SVC_GW_WEBHOOK_0008 is run
- **THEN** it passes

WHEN the subscribable event vocabulary is read
THEN every event it offers SHALL be namespaced under the subject it is about,
AND no superseded spelling SHALL appear in it.

WHEN a subscriber is registered with a filter naming an event outside the
published vocabulary
THEN the registration SHALL be refused rather than accepted and never matched.

WHEN a subscribed event is delivered
THEN the delivery SHALL carry the same namespaced name in its event header and
in its body.

### Requirement: GW_WEBHOOK_0009
The system SHALL implement GW_WEBHOOK_0009.

#### Scenario: SVC_GW_WEBHOOK_0009
- **WHEN** SVC_GW_WEBHOOK_0009 is run
- **THEN** it passes

### Requirement: GW_API_0005
The system SHALL implement GW_API_0005.

#### Scenario: SVC_GW_API_0005
- **WHEN** SVC_GW_API_0005 is run
- **THEN** it passes

### Requirement: GW_API_0006
The system SHALL implement GW_API_0006.

#### Scenario: SVC_GW_API_0006
- **WHEN** SVC_GW_API_0006 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0010
The system SHALL implement GW_WEBHOOK_0010.

#### Scenario: SVC_GW_WEBHOOK_0010
- **WHEN** SVC_GW_WEBHOOK_0010 is run
- **THEN** it passes

### Requirement: GW_WEBHOOK_0011
The system SHALL implement GW_WEBHOOK_0011.

#### Scenario: SVC_GW_WEBHOOK_0011
- **WHEN** SVC_GW_WEBHOOK_0011 is run
- **THEN** it passes
