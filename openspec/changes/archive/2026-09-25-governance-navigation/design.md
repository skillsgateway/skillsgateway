# Design: governance-navigation

## Context

See proposal.md, "Why". The sidebar is one static `groups` array in
`app-layout.tsx`. `marketplace-task-navigation` added one nested block,
`MarketplaceSections`: it is route-derived, shown only inside a marketplace,
and rendered as an indented `ul` under the Marketplaces entry. The audit page
(`audit.tsx`) holds the ledger, the NDJSON download and the whole sink UI:
the create form, the table, the show-once `SinkSecretDialog` and cursor reset.
`webhooks.tsx` holds the subscriber form above the subscriber and delivery
tables.

Authorization is unchanged and already asymmetric. Reading subscribers,
deliveries and sinks requires the auditor role. Creating, deleting and cursor
resets require admin.

## Goals / Non-Goals

**Goals:** the sidebar regroups, the sinks UI moves, the two integration pages
share one layout, and the moved addresses are kept.

**Non-Goals:** no change to any API, to what a sink or subscriber *is*, or to
the vetting-chain page's content (only its label changes). No per-marketplace
adoption (parked in the proposal).

## Decisions

1. **Integrations nests always, not by route.** The marketplace block expands
   only inside a marketplace, because a marketplace is one of many. Integrations
   has exactly two fixed sections, so hiding them until the reader is already
   there only adds a click. The sections render under the Integrations entry on
   every page, with the same indented-`ul` styling as `MarketplaceSections`.
   Considered: a tab strip inside one `/integrations` page. Rejected because a
   tab has no sidebar entry, and PR #482 set the precedent that sections are
   addressable and listed in the navigation.

2. **Integrations is a label, not a link.** The parent entry names the group.
   `/integrations` itself redirects to `/integrations/webhooks`, so a typed or
   bookmarked parent address lands somewhere.

3. **A shared `IntegrationSection` layout, not a shared generic table.** It
   takes the heading, the one-line description, the admin-only **Add…** button
   and the add dialog's content, then renders the list and its state below.
   The webhook and sink tables differ in columns (event filter vs ledger
   position and cursor reset) and stay as they are. Only the frame is shared.
   Considered: one generic "outbound target" table. Rejected because it couples
   two API shapes for a saving of ~30 lines.

4. **Add opens a dialog, and the secret dialog follows it.** The existing
   inline form moves unchanged into `Dialog` (the name, URL and events fields,
   and the same client-side validity rules and hints). On success the add dialog
   closes and the existing show-once secret dialog opens. Tests keep finding the
   fields by label, so their queries survive the move.

5. **Write controls are offered only to an administrator.** Today every session
   sees the form, and a non-admin's submit fails with a toast. The pages now
   read `useIsAdmin()` and render **Add…**, remove and cursor reset only for an
   admin. The server's refusal is unchanged and remains the authority. This
   follows PR #473's rule that the interface refuses what the server would
   refuse.

6. **Redirects are client-side `<Navigate replace>`.** GW_AUTH_0046 — Personal
   access tokens are a per-user surface reached from the user menu set the rule
   that a moved entry keeps its address. `/tokens` kept its route, whereas here
   the page itself moves, so the old address redirects. `SpaController` keeps `/webhooks` and
   adds `/integrations`, `/integrations/webhooks` and `/integrations/sinks`, so
   each resolves on a cold load (`SpaRoutesTests` reads the route list from
   `main.tsx`).

7. **Download is a header action on the audit page.** It becomes an outline
   button right-aligned beside the `h1`, the same place the marketplace header
   carries Ingest. The export explainer shrinks to the button's accessible
   description plus the existing docs link.

8. **Group names: Oversight and Configuration.** "Oversight" is what an auditor
   does with the ledger and the adoption report. "Configuration" matches the
   marketplace's own *Settings* section closely without reusing the word.

## Risks / Trade-offs

- [Muscle memory: auditors look for sinks on Audit log] → The audit page links
  to "Audit sinks" beside Download ("Pushed to N sinks → Audit sinks").
- [e2e specs click sidebar links by name] → They are updated in this change.
  The names become "Webhooks" (still unique) and "Audit sinks".
- [The Configuration group shows a Vetting chain entry only to admins, so
  non-admins see Integrations alone] → Acceptable, and the same rule as today.

## Migration Plan

Frontend only, apart from the SPA route list. Rollback is a revert. The old
`/webhooks` address remains served, so no bookmark breaks in either direction.

## Requirements

- GW_AUDIT_0006 — Portal audit export surface: revision 0.2.0. The sink listing
  moves to the integrations surface. SVC_GW_AUDIT_0006 follows: the download is
  on the audit page, and the sink is listed on the audit sinks page.
- GW_AUTH_0048 — Portal navigation separates records from configuration and
  keeps moved addresses: new, with SVC_GW_AUTH_0048 (a vitest over the nav
  groups and entries, and the `/webhooks` redirect).
