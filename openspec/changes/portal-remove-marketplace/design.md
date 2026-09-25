# Design: portal-remove-marketplace

## Context

See proposal.md for the motivation. The server side is complete (#477,
archived as `2026-09-23-remove-marketplace`): `DELETE
/api/v1/marketplaces/{name}` takes `{"reason": …}`, is admin-only, answers `422`
for a blank reason and `404` for a name with no live marketplace, and returns
the withdrawn snapshot ids and the tokens that lost a publication grant. It
does not refuse a marketplace that the declarative estate declares. The next
reconciliation, which runs at startup or on `POST /api/v1/estate/reconcile`,
registers the name again as a new marketplace. The estate guide tells operators
to remove the declaration first.

The Settings section already shows administrator-only content (the vetting
chain) by rendering it only when `useIsAdmin()` is true.

## Goals / Non-Goals

**Goals:** a portal route to the existing removal, with the consequence stated
before the act, and no control that would quietly be undone.

**Non-Goals:** editing the clone URL (parked by the issue); any server change;
refusing removal of a declared marketplace on the server; an undo.

## Decisions

1. **A plain confirmation dialog, not type-to-confirm.** The portal has no
   type-the-name pattern, and every other destructive control (token
   revocation, sink and subscriber deletion, snapshot deletion) is a single
   click. This one is irreversible, so it gets a dialog. The dialog already asks
   for a typed act, the reason the server requires. Adding a second typed field
   that only repeats the name would add friction and protect nothing more. The
   dialog title and the confirm button both name the marketplace
   ("Remove corp-skills").
2. **The reason field mirrors the server.** Required and trimmed, with nothing
   stricter. The confirm button is disabled until the field holds a non-blank
   value, with a hint bound by `aria-describedby`, following the form rules.
3. **Placement: a card at the end of Settings, not a header action.** The
   header holds the actions used every day (Ingest, Connect a client). Removal
   is rare and destructive, so it goes last on the page that describes what was
   registered.
4. **Non-administrators do not see the card.** This follows the vetting chain
   on the same page. A disabled control would advertise an action that the
   reader can never take.
5. **Declared marketplaces: disabled, with the reason on screen.** The portal
   reads the last estate reconciliation report (`GET /api/v1/estate`, which
   admins may read). The marketplace counts as declared when that report has a
   `marketplace` entry with its name, whatever the entry's action, because the
   declaration exists and the next run acts on it. The report reflects the
   configuration the running gateway loaded, and changing the declaration
   requires a restart, which produces a new report. So the report is current
   whenever it matters. While the report loads, the button is disabled. If the
   report cannot be read (for example `404`, meaning no run yet), the
   marketplace is treated as not declared. The server is authoritative and
   permits the removal, so the portal does not invent a refusal.
   *Alternative considered:* show a warning and let the removal go ahead. It was
   rejected because the removal would be silently turned into a fresh, empty
   marketplace of the same name at the next restart.
6. **Afterwards, the marketplace list.** The marketplace's page no longer
   exists. The portal navigates to `/marketplaces` before refreshing the
   listing, so the layout never flashes "not found", and a toast states how many
   approved snapshots were withdrawn.
7. **Errors.** Every refusal (`403`, `404` after a concurrent removal, `422`)
   is shown as a toast with the server's `detail`, and the dialog stays open.
   The removal endpoint has no `409`: a refusal changes nothing, so it can
   simply be retried.

## Risks / Trade-offs

- [The estate report is held in memory and could predate a configuration
  change] → The configuration is loaded once at startup, and the startup run
  replaces the report, so the report cannot fall behind a declaration.
- [An administrator removes a declared marketplace through the API anyway] →
  This is unchanged from #477 and documented in the estate guide.

## Migration Plan

None. The change is portal-only and additive.
