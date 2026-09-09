# admin-portal Specification

## Purpose

The browser portal: marketplace and snapshot administration and personal access
token self-service, served by the gateway itself behind the OIDC session.
## Requirements
### Requirement: GW_INGEST_0007
The system SHALL implement GW_INGEST_0007.

The portal's marketplace administration is presented as a sortable table with one
row per marketplace (name linking to its detail page, source, latest snapshot
state and vetting outcome, upstream-updated, snapshot count) that expands in place
to the snapshot review sub-table. The register form warns before submitting an
already-registered clone URL and requires acknowledgement to proceed, without
blocking the legitimate same-URL-under-a-new-name case. This is a presentation and
navigation change only; the requirement's ID-level statement and its verification
are unchanged.

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

