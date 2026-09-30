# Proposal: bound-waiver-lifetime

## Why

Issue [#542](https://github.com/skillsgateway/skillsgateway/issues/542).
`WaiverService.create` requires an expiry and requires it to lie in the future;
nothing bounds how far. The portal tells the reviewer "there are no unlimited
waivers", which is true only in the letter: a fifty-year waiver is accepted.
A waiver is the one act that turns a blocked verdict into an approval.

## What Changes

- **A waiver may run for at most 90 days.** An expiry more than 90 days after
  the moment of creation is refused, never clamped; exactly 90 days is accepted.
  The bound is a constant, `WaiverService.MAX_LIFETIME`, not a configuration
  leaf (design D1).
- **Same refusal shape.** It is a `WaiverValidationException`, so the API answers
  400 "Waiver rejected" like every other waiver refusal.
- **Portal.** The expiry date input carries a `max` of the last day the server
  accepts, and the form will not enable "Record waiver" past it.
- **Docs.** The waiving guide, the vetting concept page and the waiver POST
  reference state the bound.

**Not in scope:** a configuration leaf, per-marketplace lifetimes, renewing a
waiver in place (a new waiver is recorded instead).

## Impact

- Requirement `GW_VETTING_0007 — Scoped, expiring vetting waivers` amended
  (revision bump); new `SVC_GW_VETTING_0007.2`.
- `WaiverService`, `vetting-report.tsx`, three docs pages.
- No OpenAPI schema change; a longer expiry is a validation refusal (400).
