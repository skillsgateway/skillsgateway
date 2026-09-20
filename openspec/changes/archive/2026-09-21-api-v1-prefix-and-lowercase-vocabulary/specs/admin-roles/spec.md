# admin-roles — delta for api-v1-prefix-and-lowercase-vocabulary

## REMOVED Requirements

### Requirement: GW_AUTH_0027

**Reason**: The requirement governed a startup refusal for
`skills-gateway.roles.enabled`, a property removed before 1.0.0. Its own text
scheduled it for removal "at the next major, once no supported version has ever read
the property", and 1.0.0 is that major. Enforcement being unconditional is stated by
`GW_AUTH_0025`, which is unaffected; what goes is only the migration aid that told an
operator their leftover manifest line had stopped being read.

**Migration**: Remove `skills-gateway.roles.enabled` and
`skills-gateway.storage.object-store.cache.ref-freshness` from any configuration that
still sets them. Both are now unknown properties and are ignored like any other,
rather than refusing startup. No behaviour they used to control is settable: role
enforcement is always on, and the reference-map freshness bound is the next
advertisement.
