# admin-portal Specification

## Purpose

The browser portal: marketplace and snapshot administration and personal access
token self-service, served by the gateway itself behind the OIDC session.
## Requirements
### Requirement: GW_INGEST_0007
The system SHALL implement GW_INGEST_0007.

The portal's marketplace administration is presented as a sortable table with one
row per marketplace (name linking to its page, source, latest snapshot state and
vetting outcome, upstream-updated, snapshot count, and the count of snapshots
awaiting a decision, linking to that marketplace's review). The table does not
expand in place and carries no decision control: approve and reject are offered
only on the marketplace's review, beside the evidence they rest on. The register
form warns before submitting an already-registered clone URL and requires
acknowledgement to proceed, without blocking the legitimate same-URL-under-a-new-name
case.

A marketplace's page is divided into addressable sections — review, snapshots,
activity and settings — which the portal's navigation lists beneath the open
marketplace. The marketplace's header, present on every section, states what the
facade serves and carries the marketplace-level actions, ingestion among them. An
address naming a snapshot, tab and path on the marketplace's page resolves to the
same snapshot, tab and path on its review.

The portal's navigation SHALL additionally offer, beside the API reference, a
link to the project documentation site and a link to the project source
repository. Both SHALL open in a new browsing context, SHALL be announced as
doing so through their accessible name rather than by an icon alone, and SHALL
carry no document-opener reference. Neither is operator-configurable: the two
addresses are the project's own published coordinates.

This is a presentation and navigation change only; the requirement's ID-level
statement and its verification are unchanged.

#### Scenario: SVC_GW_INGEST_0007
The system SHALL pass SVC_GW_INGEST_0007.

### Requirement: GW_AUTH_0005
The system SHALL implement GW_AUTH_0005.

#### Scenario: SVC_GW_AUTH_0005
The system SHALL pass SVC_GW_AUTH_0005.

### Requirement: GW_AUTH_0014
The system SHALL implement GW_AUTH_0014.

#### Scenario: SVC_GW_AUTH_0014
The system SHALL pass SVC_GW_AUTH_0014.


### Requirement: GW_AUTH_0043
The system SHALL implement GW_AUTH_0043.

#### Scenario: SVC_GW_AUTH_0043
The system SHALL pass SVC_GW_AUTH_0043.

### Requirement: GW_AUTH_0044
The system SHALL implement GW_AUTH_0044.

#### Scenario: SVC_GW_AUTH_0044
The system SHALL pass SVC_GW_AUTH_0044.

### Requirement: GW_AUTH_0045
The system SHALL implement GW_AUTH_0045.

#### Scenario: SVC_GW_AUTH_0045
The system SHALL pass SVC_GW_AUTH_0045.

### Requirement: GW_AUTH_0046
The system SHALL implement GW_AUTH_0046.

#### Scenario: SVC_GW_AUTH_0046
The system SHALL pass SVC_GW_AUTH_0046.

### Requirement: GW_AUTH_0047
The system SHALL implement GW_AUTH_0047.

#### Scenario: SVC_GW_AUTH_0047
The system SHALL pass SVC_GW_AUTH_0047.

### Requirement: GW_INGEST_0037
The system SHALL implement GW_INGEST_0037.

#### Scenario: SVC_GW_INGEST_0037
The system SHALL pass SVC_GW_INGEST_0037.

### Requirement: GW_AUTH_0048
The system SHALL implement GW_AUTH_0048.

#### Scenario: SVC_GW_AUTH_0048
The system SHALL pass SVC_GW_AUTH_0048.
