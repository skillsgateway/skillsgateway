# License compliance for skills

GitHub issue #30.

## Why

The gateway vets ingested skill content for secrets and prompt injection, but
says nothing about the legal terms under which that content may be used. An
organisation adopting third-party skill marketplaces needs to know what
license each snapshot ships under, to refuse licenses its policy bans (or
allow only a vetted set), and to answer license questions from the same
supply-chain surface that already answers "what is in this snapshot" — the
SBOM/content story. Today a reviewer would have to read every LICENSE file by
hand, and nothing stops an AGPL-only or unlicensed repository from being
approved unnoticed.

## What Changes

- **A built-in `license-scan` vetting connector** that runs, like the secret
  and prompt-injection scanners, at ingestion (and on every re-vetting pass)
  against the quarantined, SHA-pinned snapshot. It detects licenses
  deterministically — SPDX identifiers resolved from `LICENSE`/`LICENCE`/
  `COPYING`/`UNLICENSE` files, `SPDX-License-Identifier` tags inside them,
  and the `license` metadata fields of the marketplace manifest — with no
  heuristic scoring. A license text that matches no known SPDX fingerprint is
  recorded as **unknown license**, a first-class detectable state with its
  own finding rule id, and a snapshot carrying no license information at all
  is recorded as **missing license**, likewise its own rule id.
- **Org-level allow/ban lists as configuration**
  (`skills-gateway.vetting.license.allowed` / `.banned`, SPDX ids). A
  detected license on the ban list is a blocking finding; when a non-empty
  allow list is configured, any detected license not on it — and any unknown
  or missing license — is a blocking finding. With neither list configured
  (the default), detected licenses are informational and unknown/missing
  licenses are warnings, so no existing estate is blocked by upgrading.
- **Violations flow through the standard path** — nothing parallel is
  invented: license findings are ordinary vetting findings, aggregated
  fail-closed by the existing chain, gating approval through the existing
  gate, waivable per rule id through the existing scoped/expiring waiver
  mechanism, re-checked by continuous re-vetting, and written to the
  append-only ledger like every other verdict. The connector's recorded
  version reflects the policy in force, so a changed allow/ban list is
  attributable in the chain identity exactly as a changed scanner rule set
  is (GW_0049).
- **License data surfaced over the API**: a new
  `GET /api/snapshots/{id}/licenses` endpoint reports, for any snapshot, each
  detected license with its SPDX id (or unknown), where it was found (file
  path or manifest field), and its policy evaluation — complementing the
  snapshot content inventory and the gateway's own SBOM endpoint as the
  supply-chain answer for served content. REST-only in v1; the portal already
  shows the license findings on the snapshot review surface through the
  existing vetting/findings UI, so no new portal page is added.

## Capabilities

### New Capabilities

- `license-compliance`: deterministic license detection at ingestion, the
  configured allow/ban policy evaluated through the standard vetting chain,
  unknown/missing license as detectable states, and the per-snapshot license
  report endpoint (GW_0089–GW_0091).

### Modified Capabilities

None — the vetting chain, waivers, approval gate, and re-vetting requirements
(GW_0037–GW_0055) already quantify over "every connector"; adding a connector
changes no existing requirement.

## Impact

- New: `LicenseScanConnector`, `LicenseDetector`, license policy properties
  under `skills-gateway.vetting.license.*`, license report service and
  endpoint, reqstool requirements GW_0089–GW_0091 with SVC_GW_0089–0091.
- Touched: `SkillsGatewayProperties.Vetting` (new nested `License` record),
  `AdminController` (new read endpoint), `VettingTests` (a chain-position
  assertion that hard-codes two connectors becomes derived — no assertion is
  weakened), OpenAPI snapshot and generated TS types.
- Docs: configuration reference, marketplaces/snapshots API reference,
  vetting concept page, new "License compliance" guide.
- Estate note: the allow/ban lists are deliberately **configuration, not
  API-managed runtime state** (see design.md), so no `skills-gateway.estate.*`
  extension is required — the policy is already declarative by construction.
