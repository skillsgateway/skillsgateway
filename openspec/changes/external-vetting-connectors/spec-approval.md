# Executable spec — external-vetting-connectors (old-coder SPEC)

**Spec approval: not obtained (autonomous run).** This document is the artifact
the owner reviews after the fact; confidence is claimed correspondingly lower, and
the container-backed gates are deferred to a serial pre-merge run (see
`evidence.md`).

## Setup plan

- **No new dependencies.** Jackson and Spring `RestClient` are present; the tests
  use the JDK's `com.sun.net.httpserver.HttpServer` (test scope) as a real
  in-process endpoint.
- Git checkpoints at spec and each green step, under the repo's existing
  old-coder authorization.

## Behaviours (each maps to a test)

### GW_0142 — a configured external connector joins the ordered chain

- `external[0]` with name/url/order/version/description binds to a connector that
  appears in `VettingService.connectors()` at its `order`, alongside the
  unremoved built-ins, and is named in `chainIdentity()` as `name@version`.
  → `ExternalConnectorRegistrationTests.aConfiguredExternalConnectorJoinsTheChainInItsConfiguredPosition`
- A `pass` answer is recorded as a `PASS` verdict at position 0 and the run
  clears; the POST body carries the snapshot sha and the skill's text content.
  → `ExternalVettingConnectorTests.anExternalConnectorPassIsRecordedAndClears`,
  unit `aPassIsMappedToAPassVerdict`, `theConfiguredCredentialIsSentToTheEndpoint`,
  `aBinaryFileIsShippedUnscannedNotDropped`.

### GW_0143 — fail closed on any inconclusive answer

Each of these records `ERROR` and a blocked outcome, and never clears:

- unreachable endpoint (connection refused);
- non-2xx status;
- read timeout (endpoint hangs past `read-timeout`);
- empty body; JSON `null` body; unparseable body;
- unrecognised `state`; missing `state`; a declared `error` state;
- malformed finding (unknown severity; missing id);
- oversized response (over `max-response-bytes`);
- snapshot bundle over `max-request-bytes`.

And a snapshot so blocked stays `HELD` and `approve` throws `VettingBlockedException`.
→ unit tests (each branch) + `ExternalVettingConnectorTests.everyInconclusiveAnswerFromTheEndpointBlocks`.

### GW_0144 — worst-of, and the report link and findings are recorded

- `fail` + findings + `reportUrl` → `FAIL`, findings and report link persisted
  through the verdict model.
- declared `pass` + a `critical` finding → recorded `FAIL` (worst-of), blocks.
- `info`-only findings → still `PASS`.
→ `ExternalVettingConnectorTests.aFailWithFindingsBlocksAndPersistsFindingsAndReportUrl`,
  `aPassDeclaredAlongsideACriticalFindingIsRecordedAsFailAndBlocks`, unit
  `aFailWithFindingsCarriesFindingsAndReportUrl`, `aPassAlongsideACriticalFindingIsRecordedAsFailByWorstOf`,
  `informationalFindingsAloneStillPass`.

### GW_0145 — a `pending` answer blocks as the async seam

- `pending` → `PENDING` verdict, blocked outcome, does not clear, snapshot stays
  held and cannot be approved.
→ `ExternalVettingConnectorTests.aPendingAnswerBlocksAsTheAsyncSeam`, unit
  `aPendingAnswerIsRecordedAsPendingWhichBlocks`.

## Negative constraints (must survive)

- The built-in connectors and their SVCs (SVC_GW_0037–0043) are unchanged.
- `VettingService`, `VettingChain`, `Verdict`, `VerdictState`,
  `VettingRepository` are unchanged (external connectors join as beans and record
  through the existing model).
- No served API endpoint is added or changed (`openapi.json` unchanged).
- A deployment that configures no external connector behaves exactly as before.
