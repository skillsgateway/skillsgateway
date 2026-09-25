# Tasks: governance-navigation

## 1. Requirements (reqstool)

- [x] 1.1 Revise GW_AUDIT_0006 — Portal audit export surface (description, rationale) and SVC_GW_AUDIT_0006 so that the download is on the audit page and the sinks are listed on the audit sinks page; revision 0.2.0
- [x] 1.2 Add GW_AUTH_0048 — Portal navigation separates records from configuration and keeps moved addresses, and SVC_GW_AUTH_0048. Re-check first that no in-flight change or open PR has taken the id

## 2. Routing and shell

- [x] 2.1 Routes in `main.tsx`: `/integrations/webhooks` and `/integrations/sinks`; `/integrations` redirects to `/integrations/webhooks`; `/webhooks` redirects to `/integrations/webhooks`
- [x] 2.2 Add `/integrations`, `/integrations/webhooks` and `/integrations/sinks` to `SpaController`, keeping `/webhooks`; `SpaRoutesTests` stays green
- [x] 2.3 Sidebar groups **Oversight** (Audit log, Adoption) and **Configuration** (Vetting chain, admin-only; Integrations with nested Webhooks and Audit sinks, always shown); breadcrumb names each entry
- [x] 2.4 Rename the page heading and refusal text from "Vetting" to "Vetting chain"

## 3. Pages

- [x] 3.1 Audit log: the ledger is the page content, Download (NDJSON) is a header action, and a line links to Audit sinks; the sink form and table are removed
- [x] 3.2 Shared `IntegrationSection` frame: heading, description, an admin-only **Add…** button that opens a dialog, then the list below
- [x] 3.3 Webhooks section on the frame: the subscriber form moves into the add dialog; subscribers, then delivery attempts; remove only for an admin
- [x] 3.4 Audit sinks section on the frame: the sink form moves into the add dialog, then the show-once secret dialog; sinks with position; remove and cursor reset only for an admin

## 4. Tests

- [x] 4.1 Move the sink tests from `audit.test.tsx` to a sinks page test (fields found by label inside the dialog); the audit test keeps the download assertion (@SVCs SVC_GW_AUDIT_0006)
- [x] 4.2 `webhooks.test.tsx` follows the dialog; add an assertion that a non-admin sees no Add or remove control
- [x] 4.3 Navigation test: the groups and their entries, Integrations' nested sections, the admin-only Vetting chain entry, and `/webhooks` resolving to the webhooks section (@SVCs SVC_GW_AUTH_0048)
- [x] 4.4 e2e `portal.spec.ts`: the webhook and audit-sink flows reach their pages through the new sidebar names and add through the dialog; the Vetting heading assertions read "Vetting chain"

## 5. Docs

- [x] 5.1 Update `docs/manual/` portal pages: the audit log, webhooks, export sinks and the portal navigation overview
