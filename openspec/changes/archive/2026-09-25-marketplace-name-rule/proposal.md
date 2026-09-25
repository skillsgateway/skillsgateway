# Proposal: marketplace-name-rule

## Why

A tester on a trial deployment met the marketplace name rule at registration and
could not tell why it exists. The rule is there because the name is the facade
path segment `/git/<name>` that clients clone, and it is the one identifier a
marketplace has — there is no separate display name, and there will not be one.
The rule also has no upper bound today, so a name can be as long as a URL
allows.

## What Changes

- **The rule says why, where people meet it.** The registration refusal (422)
  and the portal's registration form hint and field error each say that the name
  is the `/git/<name>` path clients clone. An estate declaration goes through the
  same registration gate, so it carries the same reason.
- **BREAKING: a marketplace name is at most 63 characters** (a DNS-label-like
  bound). Enforced everywhere the character rule is enforced: API registration
  of upstream and hosted marketplaces, estate declarations, the git facade's
  fetch and publish paths and the revocation-status check, the portal form, and
  a `CHECK` on the `marketplaces` table.
- **One rule in the code.** The four copies of the name pattern in the backend
  collapse into one shared definition, so the facade rejects exactly what
  registration rejects.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `marketplace-ingestion`: adds GW_INGEST_0063 — Marketplace names are one
  bounded facade path segment, and GW_INGEST_0064 — A refused marketplace name
  says it is the clone path. No existing requirement states the name rule, so
  there is nothing to amend; GW_ESTATE_0002 — Declared marketplaces pass the
  registration trust boundary already routes declarations through "the
  marketplace name rules" and needs no change.

## Impact

- API: `POST /api/v1/marketplaces` narrows `name` (`maxLength: 63`). A client
  registering a longer name now gets 422. Declared as a breaking change.
- Estate schema: `skills-gateway.estate.marketplaces[].name` narrows the same
  way (enforced by the shared registration gate).
- Schema: `V1__init.sql` `marketplaces.name` CHECK gains the bound (edited in
  place, pre-1.0).
- Portal: registration form rule, hint and error text.
- Docs: the pages that state the name rule gain the length limit.
