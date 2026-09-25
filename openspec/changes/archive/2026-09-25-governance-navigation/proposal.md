# Proposal: governance-navigation

## Why

The sidebar's **Governance** group puts four unlike things side by side: two
records an auditor reads (the audit ledger, the adoption report) and two
surfaces an administrator configures (the estate-wide vetting chain, webhook
subscribers). A third configured surface, audit export sinks, is not in the
navigation at all: it is a create form and a table on the Audit log page,
placed *above* the ledger. So the page an auditor opens to read the record
leads with an administrator's form, and the record is below the fold. This is
the "settings before the work" ordering that `marketplace-task-navigation`
removed from the marketplace page.

Export sinks and webhook subscribers are the same kind of object: a named
outbound HTTP target, a signing secret shown once, and a delivery position or
history an operator watches to see whether it is keeping up. They are managed
on two different pages with two different layouts.

"Vetting" also names two things: the snapshot tab reviewers use on every
decision, and the administrator-only page that sets the estate's default chain.

## What Changes

- **The Governance group is split in two.** **Oversight** holds what is read:
  **Audit log** and **Adoption**. **Configuration** holds what is set:
  **Vetting chain** and **Integrations**.
- **Audit log leads with the ledger.** The ledger download becomes a header
  action. The export sink form and table leave the page.
- **Integrations has sections, and the sidebar lists them.** The Integrations
  entry expands to **Webhooks** (`/integrations/webhooks`) and **Audit sinks**
  (`/integrations/sinks`), the same nested pattern the open marketplace uses.
  Both sections share one layout: the list first, then its delivery state, and
  **Add…** opens a dialog rather than a form above the list.
- **Vetting is renamed Vetting chain** in the navigation, the breadcrumb and the
  page heading. It stays administrator-only, as it is today. The address
  `/vetting` is unchanged.
- **Old addresses keep working.** `/webhooks` redirects to
  `/integrations/webhooks`, and `/integrations` to its first section.
- **The Configuration group is shown to every session.** Auditors can read
  webhooks and sinks today. Their add and remove controls are offered only to
  an administrator, and the server refuses them otherwise, as it already does.

No API change. No backend change beyond the SPA route list.

**Parked, not proposed:** each marketplace's adoption shown in its Activity
section. It would fit there, but it is new presentation scope, not a
restructure.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `audit-export`: GW_AUDIT_0006 — Portal audit export surface. The download stays
  on the audit page. The sink listing moves to the portal's integrations
  surface, beside the webhook subscribers.
- `admin-portal`: adds GW_AUTH_0048 — The portal's navigation separates the
  records it reads from the configuration it sets, groups the outbound
  integrations under one entry, and keeps a moved page's earlier address
  resolvable.

GW_WEBHOOK_0004 — Portal webhook administration and GW_VETTING_0035 — Estate-wide
portal governance of the vetting chain keep their wording. Both still describe
"a portal page" and "an administrator-only surface", and both still hold after
the move and the rename.

## Impact

- `src/main/frontend/src/`: `components/app-layout.tsx` (groups, nested
  Integrations sections, labels, breadcrumb), `pages/audit.tsx` (sinks removed,
  download to the header), `pages/webhooks.tsx` and a new sinks section under an
  integrations layout, `main.tsx` routes and the `/webhooks` redirect.
- `SpaController` gains `/integrations`, `/integrations/webhooks` and
  `/integrations/sinks`. `/webhooks` stays served so the redirect resolves on a
  cold load.
- Tests: `audit.test.tsx` and `webhooks.test.tsx` follow the move, a new
  navigation test covers the groups and the redirect, and the e2e specs that
  reach these pages by sidebar name are updated.
- Docs: the portal pages under `docs/manual/` for the audit log, webhooks,
  export sinks and the navigation.
- reqstool: GW_AUDIT_0006 is revised, with SVC_GW_AUDIT_0006 following the
  sinks to their new page. GW_AUTH_0048 is added with SVC_GW_AUTH_0048.
