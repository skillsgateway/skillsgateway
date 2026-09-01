# Proposal: external-vetting-connectors

## Why

Vetting connectors are compile-time Spring beans (`SecretScanConnector`,
`PromptInjectionConnector`, `LicenseScanConnector`). Adding an organisation's own
connector — an LLM endpoint IT Security has prepared, a sandbox, a corporate
scanner — means changing code. Issue
[#222](https://github.com/skillsgateway/skillsgateway/issues/222) asks for what
[#10](https://github.com/skillsgateway/skillsgateway/issues/10) earmarked but
never built: an operator-configurable external connector returning the normalized
`{verdict, report-url, findings[]}`. `docs/manual/concepts/vetting.md` still says
an LLM review connector "is not part of this chain", and `Verdict` already carries
`report-url` for exactly this.

This is a **trust-boundary** change: the vetting chain is the approval gate's
evidence, and an external connector reaches a dependency the gateway does not
control. The design is dominated by one property — a timeout, outage, hostile
answer or truncated request must never be indistinguishable from a clean pass
(`RevetVerdict`'s fail-closed note, applied to a network boundary).

## What Changes

- **A configured external connector SPI.** `skills-gateway.vetting.external[*]`
  (name, url, order, version, description, credential, timeouts, size caps) binds
  to one `ExternalVettingConnector` bean each, which joins the existing
  `List<VettingConnector>` chain — so ordering, recording and fail-closed
  aggregation apply unchanged, and `VettingService` is untouched.
- **Synchronous request/response is the default.** The gateway POSTs the snapshot
  bundle (`{snapshotId, marketplace, sha, files[]}` — the scannable content a
  built-in walks, since quarantined content is never served) and reads back the
  normalized `{state, reportUrl, findings[]}`.
- **Fail-closed at every branch.** Unreachable, refused, connect/read timeout,
  non-2xx, empty, oversized, unparseable, unrecognised state, malformed finding,
  or a snapshot bundle over the request cap — each is an `ERROR` verdict, which
  blocks. No branch clears without a well-formed, in-bounds, understood answer.
- **Worst-of.** The recorded state is the more severe of the declared state and
  the state the endpoint's own findings imply, so `pass` alongside a `critical`
  finding is recorded as `fail`.
- **Asynchronous seam.** A `pending` answer is recorded as `PENDING`, which
  blocks until resolved. The inbound resolution callback is deliberately a
  separate, later capability; an unresolved `pending` simply keeps the snapshot
  held, which is the correct default.
- **ADR 0009** records the contract; new requirements **GW_0142**–**GW_0145**
  with SVCs cover it.

Requirement ids start at GW_0142 because GW_0132–GW_0141 are claimed by in-flight
changes on other branches (`fix-discarded-ref-update-results`,
`remove-roles-enabled-toggle`).

## Capabilities

### New Capabilities

_None._ The behaviour extends `snapshot-vetting`.

### Modified Capabilities

- `snapshot-vetting`: gains GW_0142 (operator-configured external connectors join
  the ordered chain), GW_0143 (an external connector fails closed on any
  inconclusive answer), GW_0144 (worst-of, and the report link and findings are
  recorded through the existing verdict model), GW_0145 (a `pending` answer is
  the asynchronous seam and blocks until resolved).

## Impact

- **Backend**: new `vetting/ExternalConnectorProperties`,
  `ExternalVetRequest`, `ExternalVetResponse`, `ExternalVettingConnector`,
  `ExternalVettingConnectorRegistrar`, `ExternalVettingConfiguration`. No change
  to `VettingService`, `VettingChain`, `Verdict`, `VerdictState`,
  `VettingRepository` or the built-in connectors — the external connectors join
  the chain as ordinary beans and record through the existing model, so #221
  (audit enrichment) and #223 (cockpit override), which touch the same package on
  other branches, do not conflict with these additions.
- **API**: none. The external contract is an outbound HTTP call, not a served
  endpoint, so `openapi.json` is unchanged and the contract gate has nothing to
  diff. There is **no** portal change here — portal surfacing of external
  verdicts is #224.
- **Persistence**: none. The `vetting_verdicts.report_url` column and the finding
  tables already hold everything an external verdict carries.
- **Docs** (same PR): `reference/configuration.md` (External connectors),
  `concepts/vetting.md` (external connectors; the LLM-review note), ADR 0009 and
  `reference/decisions.md`.
- **Requirements**: `docs/reqstool/requirements.yml` and
  `software_verification_cases.yml` — GW_0142–GW_0145 and their SVCs.
- **Trust boundary**: this is vetting-chain evidence, so the
  `.claude/skills/old-coder` discipline applies — adversarial/negative tests
  proving a hostile or unreachable endpoint fails closed, manual mutation, and an
  `evidence.md`.
- **Declarative estate**: no new API-managed runtime state — external connectors
  are configuration for the per-run attribution reason in ADR 0009 — so
  `skills-gateway.estate.*` needs no extension.
