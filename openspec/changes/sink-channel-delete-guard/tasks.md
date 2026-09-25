# Tasks: sink-channel-delete-guard

Data-loss stakes: work under `.claude/skills/old-coder`. Each negative test is
written first and shown to fail against the unfixed code, and that failure is
recorded in `evidence.md`.

## 1. Requirements (reqstool)

- [ ] 1.1 Add GW_WEBHOOK_0011 — An audit sink's delivery channel is changed only through its sink, with SVC_GW_WEBHOOK_0011, covering the API delete refused with the sink named, the declarative estate entry refused, and nothing changed or recorded as changed. Re-check first that no in-flight change or open PR has taken the id
- [ ] 1.2 Revise GW_WEBHOOK_0004 — Portal webhook administration and SVC_GW_WEBHOOK_0004: lifecycle subscribers are listed; a sink's deliveries are listed, marked as the sink's, and link to where it is managed; revision 0.2.0

## 2. Tests first (shown failing)

- [ ] 2.1 `WebhookTests`: `DELETE /api/v1/webhooks/{id}` on a sink's channel answers 409 with the sink's name; afterwards the sink is still listed with its cursor, the subscriber still exists, and no `webhook-subscriber-deleted` entry was recorded (@SVCs SVC_GW_WEBHOOK_0011)
- [ ] 2.2 `WebhookTests`: a lifecycle subscriber is still deleted with 204 (the guard is not a blanket refusal)
- [ ] 2.3 `EstateReconciliationTests`: a declared webhook named like an existing sink fails its entry, naming the sink; the channel's URL, secret and events are unchanged; the declaration's other entries still apply (@SVCs SVC_GW_WEBHOOK_0011)
- [ ] 2.4 `AuditExportTests`: deleting a sink through `DELETE /api/v1/audit/sinks/{id}` removes both the sink and its channel (the positive path the reordering must keep; there is no such test today)
- [ ] 2.5 Database backstop: a raw `DELETE FROM webhook_subscribers` of a sink's channel is refused by the database
- [ ] 2.6 `GET /api/v1/webhooks` marks the sink's channel with `auditSink` = the sink's name, and a lifecycle subscriber without one

## 3. Implementation

- [ ] 3.1 `V1__init.sql`: `audit_sinks.subscriber_id … ON DELETE RESTRICT`
- [ ] 3.2 `AuditExportService.deleteSink`: delete the sink row, then the channel, in one `TransactionTemplate`; update the method comment
- [ ] 3.3 Subscriber-to-sink lookup (`AuditSinkRepository.findBySubscriberId`, and a map for the listing)
- [ ] 3.4 `WebhookController.delete`: 409 ProblemDetail naming the sink and the sink delete call; map a RESTRICT violation to the same 409; `@ApiResponse(409)`; `@Requirements GW_WEBHOOK_0011`
- [ ] 3.5 `SubscriberView.auditSink` (nullable, `@Schema`); `EstateReconciler.reconcileWebhook` refusal
- [ ] 3.6 Regenerate `openapi.json` and `types.gen.ts`; the contract gate reports additions only

## 4. Portal

- [ ] 4.1 Webhooks page: leave `auditSink` subscribers out of Subscribers; label their delivery rows "{name} · audit sink" linking to `/integrations/sinks`
- [ ] 4.2 `webhooks.test.tsx`: a sink's channel is not listed and cannot be deleted from the page; its delivery reads as the sink's and links to Audit sinks (@SVCs SVC_GW_WEBHOOK_0004)

## 5. Docs

- [ ] 5.1 Portal reference (Webhooks), the lifecycle-webhooks guide's API section (the 409 and `auditSink`), and the audit export guide (a sink is removed only through the sinks API)
