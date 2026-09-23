# snapshot-facts Specification

## Purpose

What a snapshot's pinned commit says about itself — its metadata, file
inventory, plugins and skills, and where the manifest declares each plugin —
recorded once at ingestion as queryable state, so that the policy gate reads it
rather than rebuilding it and the approval gate can ask a question of the
approved estate.

## Requirements
### Requirement: GW_INGEST_0036
The system SHALL implement GW_INGEST_0036.

#### Scenario: SVC_GW_INGEST_0036
The system SHALL pass SVC_GW_INGEST_0036.
