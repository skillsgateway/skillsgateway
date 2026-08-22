# License compliance for skills

Third-party skill content arrives with legal terms attached. The gateway
detects the licenses every snapshot declares, lets you ban or allow licenses
organisation-wide, and answers license questions per snapshot over the API —
all through the same vetting chain, approval gate and waiver mechanism that
govern secrets and prompt injection, so a banned license is exactly as loud,
as waivable and as auditable as a planted credential.

## What is detected, and how

Detection is deterministic — the same content always yields the same answer:

1. **License files**: any file named `LICENSE`, `LICENCE`, `COPYING`,
   `COPYING3` or `UNLICENSE` (case-insensitive, optional extension), at any
   depth, matched against a fixed fingerprint table of common licenses (MIT,
   Apache-2.0, the BSD/GPL/LGPL/AGPL families, ISC, MPL-2.0, EPL-2.0,
   Unlicense, CC0-1.0, CC-BY-4.0, CC-BY-SA-4.0).
2. **SPDX tags**: an `SPDX-License-Identifier:` line inside such a file wins
   outright — it is the file's own declaration.
3. **Manifest metadata**: the marketplace manifest's `license` field,
   `metadata.license`, and each plugin's `license` field, read as SPDX ids.

There is no similarity scoring. A license text or declared id the table does
not recognize is recorded as **unknown license** — its own reviewable state,
never a silent gap — and a snapshot with no license information anywhere is
recorded as **missing license**.

## Configure the policy

The allow/ban lists are configuration, applied by deploy
(see [why below](#why-the-policy-is-configuration)):

```yaml
skills-gateway:
  vetting:
    license:
      allowed: [MIT, Apache-2.0, BSD-3-Clause]   # empty = no allow list
      banned: [AGPL-3.0]
```

| Configuration | Identified license | Unknown / missing license |
| --- | --- | --- |
| Neither list (default) | Recorded informationally | Warns — visible, never blocks |
| Ban list only | Blocks if banned, informational otherwise | Warns |
| Allow list (with or without ban list) | Blocks if banned or not on the list | Blocks |

Every property is listed in the
[configuration reference](../reference/configuration.md#vetting).

## What a violation looks like

A banned or not-allowed license is an ordinary blocking finding. Approval is
refused through the standard gate, and the refusal names the finding:

```json
{"status":409,"title":"Vetting chain blocked this snapshot",
 "blockingConnectors":["license-scan"],
 "uncoveredFindings":[{"ruleId":"license-banned","location":"LICENSE"}]}
```

To accept the risk anyway, record a
[scoped, expiring waiver](waiving-findings.md) on the finding's rule id —
`license-banned`, `license-not-allowed`, `license-unknown` or
`license-missing` — exactly as for any other finding. Continuous
[re-vetting](re-vetting.md) re-runs the chain over approved content, so a
policy tightened later produces fresh violations on the estate you already
serve, in warn or enforce mode as configured.

## Read the licenses of a snapshot

```bash
curl -s https://gateway.example.com/api/snapshots/42/licenses
```

The report lists every detection with its SPDX id (or its unknown state),
where it was found, and its standing under the policy currently configured —
see the [API reference](../reference/api/marketplaces.md#get-snapshotsidlicenses).
It complements `/api/snapshots/{id}/content` and the gateway's own
`/actuator/sbom` as the supply-chain read surface.

## Why the policy is configuration

Vetting evidence must be attributable: when a snapshot that cleared last month
blocks today, was it the content or the policy? The `license-scan` connector
stamps a digest of the lists into its recorded version, which is part of every
run's chain identity — so the answer is on the record. That attribution works
because the policy changes only by deploy; there is deliberately no API that
mutates it. A GitOps deployment carries the license policy in the same file as
the rest of the [vetting configuration](../reference/configuration.md#vetting),
which also means there is nothing for
[declarative estate reconciliation](declarative-estate.md) to manage — the
policy is declarative by construction.

!!! warning "After changing the lists, re-vet"

    A policy change does not rewrite recorded runs — by design. Trigger a
    [re-vet](re-vetting.md) of the affected marketplaces to turn the new
    policy into fresh evidence, and to surface violations on content that is
    already approved and served.
