# lifecycle-webhooks Specification

## Purpose
TBD - created by archiving change add-lifecycle-event-webhooks. Update Purpose after archive.
## Requirements
### Requirement: GW_WEBHOOK_0001
The system SHALL implement GW_WEBHOOK_0001.

#### Scenario: SVC_GW_WEBHOOK_0001
The system SHALL pass SVC_GW_WEBHOOK_0001.

### Requirement: GW_WEBHOOK_0002
The system SHALL implement GW_WEBHOOK_0002.

#### Scenario: SVC_GW_WEBHOOK_0002
The system SHALL pass SVC_GW_WEBHOOK_0002.

### Requirement: GW_WEBHOOK_0003
The system SHALL implement GW_WEBHOOK_0003.

#### Scenario: SVC_GW_WEBHOOK_0003
The system SHALL pass SVC_GW_WEBHOOK_0003.

### Requirement: GW_WEBHOOK_0004
The system SHALL implement GW_WEBHOOK_0004.

#### Scenario: SVC_GW_WEBHOOK_0004
The system SHALL pass SVC_GW_WEBHOOK_0004.

### Requirement: GW_WEBHOOK_0005
The system SHALL implement GW_WEBHOOK_0005.

#### Scenario: SVC_GW_WEBHOOK_0005
The system SHALL pass SVC_GW_WEBHOOK_0005.

### Requirement: GW_WEBHOOK_0006
The system SHALL implement GW_WEBHOOK_0006.

#### Scenario: SVC_GW_WEBHOOK_0006
The system SHALL pass SVC_GW_WEBHOOK_0006.

### Requirement: GW_WEBHOOK_0007
The system SHALL implement GW_WEBHOOK_0007.

#### Scenario: SVC_GW_WEBHOOK_0007
The system SHALL pass SVC_GW_WEBHOOK_0007.

### Requirement: GW_WEBHOOK_0008
The system SHALL implement GW_WEBHOOK_0008.

The requirement is narrowed: it no longer obliges the system to rewrite an
existing subscriber's stored event filter when a name in the published
vocabulary changes. The mechanism that satisfied that obligation was a data
migration, and while the project is pre-1.0 there is no deployed database whose
rows a migration could repair. Every other obligation is unchanged — the
namespaced vocabulary, its publication as the set a subscriber may filter on,
the refusal of a filter naming an event outside it, and the same name in the
delivery's event header and in the delivered body.

#### Scenario: SVC_GW_WEBHOOK_0008
The system SHALL pass SVC_GW_WEBHOOK_0008.

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
The system SHALL pass SVC_GW_WEBHOOK_0009.

### Requirement: GW_API_0005
The system SHALL implement GW_API_0005.

#### Scenario: SVC_GW_API_0005
The system SHALL pass SVC_GW_API_0005.

### Requirement: GW_API_0006
The system SHALL implement GW_API_0006.

#### Scenario: SVC_GW_API_0006
The system SHALL pass SVC_GW_API_0006.

