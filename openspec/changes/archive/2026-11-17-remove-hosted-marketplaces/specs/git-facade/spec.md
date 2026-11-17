# git-facade — delta for remove-hosted-marketplaces

## REMOVED Requirements

### Requirement: GW_FACADE_0007

**Reason**: The gateway no longer accepts pushes. Owner-approved removal of the feature (ADR 0024, superseding ADR 0007); the verification cases SVC_GW_FACADE_0007 and SVC_GW_FACADE_0035 are removed with it, because the push endpoint, the push scope and the rule about who may be granted one no longer exist.

**Migration**: There is no write endpoint. `/publish/**` is not routed and answers as any path the gateway does not serve; a token carries no push scope, and a request for one is refused (GW_AUTH_0090). The facade remains read-only by construction (GW_FACADE_0001, GW_FACADE_0002).
