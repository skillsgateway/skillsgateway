# Proposal: credentials in held hook text are masked where it is shown

## Why

A held snapshot's hook declarations reach readers in two places: the hook list
of `GET /snapshots/{id}/content`, and the `auto-run-hook` finding that
`GET /snapshots/{id}/vetting` returns. Every portal session can make these reads,
and so can a machine credential with `marketplaces:read` or `snapshots:read`.
Both copy what the hook runs as it was written.

The secret-scan vetter reports a token in the same file without quoting it.
Copying the hook text undoes that, because the token reaches these readers
anyway.

## What Changes

- New `GW_VETTING_0065 — Credential-shaped text in held hook declarations is
  masked where the gateway shows it`. The masking covers the inventory hook's
  `runs` value and the hook part of an `auto-run-hook` message. Masked text is
  whatever the secret-scan rules would report: shaped tokens, private-key
  blocks, and high-entropy assigned values. Each is replaced by `[redacted]`.
- The vetters still read the raw text, so no verdict changes.
- New `SVC_GW_VETTING_0067`: a session with no role never reads the token
  through `/content` or `/vetting`.
- New `SVC_GW_VETTING_0068`: the redactor masks each shape and leaves other
  text unchanged.
- Not **BREAKING**: no field is added or removed. A credential-shaped value
  now reads `[redacted]`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `snapshot-vetting`: adds `GW_VETTING_0065`.

## Impact

- `SecretScanVetter.redact`, `ExecutableSurfaceVetter.describe(Hook)`, and
  `SnapshotContentService`.
- Docs: `concepts/vetting.md` and `reference/api/marketplaces.md`.

The capability map absorbs this change: it adds no package, object type, role,
sweep or configuration leaf.
