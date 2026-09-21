# observability — delta for bound-ledger-growth

## MODIFIED Requirements

### Requirement: GW_OBSERVABILITY_0003
The system SHALL implement GW_OBSERVABILITY_0003.

The always-recorded enumeration gains two gauges over the audit ledger: its
depth, and the age of its oldest entry no enabled sink has exported. They are
recorded on the same terms as the rest — under the `skills_gateway` prefix, with
fixed-vocabulary tags only, recorded whether or not telemetry export is enabled.
The second gauge is what makes an unbounded ledger visible in the deployment
where GW_RETENTION_0009 deliberately trims nothing because no sink consumes it.

#### Scenario: SVC_GW_OBSERVABILITY_0003
The system SHALL pass SVC_GW_OBSERVABILITY_0003.
