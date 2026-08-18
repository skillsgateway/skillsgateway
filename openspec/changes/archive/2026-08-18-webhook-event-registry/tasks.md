# Tasks: webhook-event-registry

## 1. Traceability (SSOT first)

- [x] 1.1 Add GW_0088 to `docs/reqstool/requirements.yml` (the served event
      registry and the portal filter composed from it).
- [x] 1.2 Add SVC_GW_0088 to `docs/reqstool/software_verification_cases.yml`.

## 2. Backend

- [x] 2.1 `GET /api/webhooks/events` on `WebhookController`, returning
      `WebhookEvent.ALL`, auditor-gated, recording nothing.
- [x] 2.2 Test: the endpoint answers every event in `ALL`, excludes
      `audit.export`, and refuses a session below auditor under enforcement.

## 3. Portal

- [x] 3.1 Query the registry; render the Events field as a checkbox list with a
      select-all control and a type-ahead filter.
- [x] 3.2 Submit `*` when all are selected, otherwise the comma-delimited names;
      keep the create control disabled until at least one event is selected.
- [x] 3.3 Mark a stored filter naming an unknown event.
- [x] 3.4 Component tests for select-all, filtering, and the wire value.

## 4. Generated artefacts and docs

- [x] 4.1 Regenerate `openapi.json` and `src/api/types.gen.ts`.
- [x] 4.2 `docs/manual/reference/api/webhooks.md` and `reference/portal.md`.

## 5. Gates and evidence

- [x] 5.1 All five gates, fresh run.
- [x] 5.2 `evidence.md`.
