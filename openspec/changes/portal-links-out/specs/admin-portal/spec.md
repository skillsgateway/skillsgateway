## MODIFIED Requirements

### Requirement: GW_INGEST_0007
The system SHALL implement GW_INGEST_0007.

The portal's marketplace administration is presented as a sortable table with one
row per marketplace (name linking to its detail page, source, latest snapshot
state and vetting outcome, upstream-updated, snapshot count) that expands in place
to the snapshot review sub-table. The register form warns before submitting an
already-registered clone URL and requires acknowledgement to proceed, without
blocking the legitimate same-URL-under-a-new-name case.

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

#### Scenario: The navigation reaches the manual and the source
- **WHEN** a signed-in user reads the portal's navigation
- **THEN** a link to the documentation site and a link to the source repository
  are present beside the API reference, each named in a way that says it opens a
  new tab, and each opening in a new browsing context without a document-opener
  reference
