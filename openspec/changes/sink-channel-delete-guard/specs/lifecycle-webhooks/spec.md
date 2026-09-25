# lifecycle-webhooks — delta for sink-channel-delete-guard

## MODIFIED Requirements

### Requirement: GW_WEBHOOK_0004
The system SHALL implement GW_WEBHOOK_0004.

The subscribers the portal lists are the lifecycle subscribers. A subscriber that
is an audit sink's delivery channel is listed with its sink on the audit sinks
surface, not among them, and its delivery attempts remain listed with the others,
marked as the sink's and linking to where the sink is managed.

#### Scenario: SVC_GW_WEBHOOK_0004
The system SHALL pass SVC_GW_WEBHOOK_0004.

## ADDED Requirements

### Requirement: GW_WEBHOOK_0011
The system SHALL implement GW_WEBHOOK_0011.

#### Scenario: SVC_GW_WEBHOOK_0011
The system SHALL pass SVC_GW_WEBHOOK_0011.
