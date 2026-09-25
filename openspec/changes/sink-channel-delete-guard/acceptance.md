# SPEC — sink-channel-delete-guard (#486)

- Tier: **3**. Data loss: a sink's removal stops the compliance feed without
  anyone asking for it.
- Setup plan:
  - Tools to install: none.
  - Isolation: branch `fix/sink-channel-delete-guard`, stacked on
    `feat/governance-navigation` (#485), in the main checkout. Not a worktree:
    the gauntlet needs the built `node_modules` and Docker (Arconia
    PostgreSQL), which a fresh worktree lacks.
  - Git: signed-off commits on the branch. The first commit is this SPEC and
    the OpenSpec change, at approval; then one commit per GREEN checkpoint;
    `evidence.md` last before the archive commit.
  - Files the gauntlet adds, by path: none. Mutation is manual (no mutation
    tool in the build); each mutant is applied and restored one at a time,
    and restoration is checked with `git diff --exit-code`.
  - Files changed, by path:
    - `src/main/resources/db/migration/V1__init.sql`
    - `…/audit/AuditExportService.java`
    - `…/persistence/AuditSinkRepository.java`
    - `…/webhook/WebhookController.java`
    - `…/estate/EstateReconciler.java`
    - `src/main/frontend/openapi.json`, `src/api/types.gen.ts` (regenerated)
    - `src/main/frontend/src/pages/webhooks.tsx`
    - tests: `WebhookTests`, `EstateReconciliationTests`, `AuditExportTests`,
      `webhooks.test.tsx`
    - reqstool YAML and docs
  - New dependencies: none.

## Failure model

| # | How this change could hurt | What catches it |
|---|---|---|
| F1 | The guard misses a path: API delete, estate update, or a future raw delete | S1, S3, S5 (database backstop) |
| F2 | The guard over-refuses: a lifecycle subscriber can no longer be deleted | S2 |
| F3 | Reordering `deleteSink` breaks sink removal, or leaves an orphan subscriber or sink | S4 (both rows gone), S4b (atomic when the second delete fails) |
| F4 | A refusal still writes something: a ledger entry, a partial update, or deleted deliveries | S1 and S3 assert state *and* the ledger are unchanged |
| F5 | The estate refusal aborts the whole reconciliation | S3 (sibling entries still apply) |
| F6 | The listing field is wrong: marks a lifecycle subscriber, or misses a channel | S6 |
| F7 | The portal still offers Delete on a channel, or hides its failing deliveries | S7, S8 |
| F8 | The contract becomes breaking | `openapi.json` diff: additions only; the contract gate in `verify` |

## Scenarios

```gherkin
Feature: an audit sink's delivery channel is changed only through its sink

  Scenario: S1 the webhooks API refuses to delete a sink's channel
    Given an audit sink "siem-<n>" with its channel subscriber
    When  an administrator calls DELETE /api/v1/webhooks/{channel id}
    Then  the answer is 409, and its detail names "siem-<n>" and DELETE /api/v1/audit/sinks/{sink id}
    And   the sink is still listed with the same cursor position
    And   the subscriber still exists, with its deliveries
    And   no "webhook-subscriber-deleted" ledger entry was recorded

  Scenario: S2 a lifecycle subscriber is still deleted
    Given a lifecycle subscriber
    When  an administrator calls DELETE /api/v1/webhooks/{id}
    Then  the answer is 204 and the subscriber is gone

  Scenario: S3 a declared webhook that collides with a sink's name fails its entry
    Given an audit sink "siem-<n>"
    And   a declared estate with a webhook named "siem-<n>" (other URL, events and secret) and one other, unrelated declared webhook
    When  the estate is reconciled
    Then  the "siem-<n>" webhook entry is reported as failed, naming the sink
    And   the channel's URL, secret and events are unchanged
    And   the unrelated webhook is created
    And   no "webhook-subscriber-updated" ledger entry names the channel

  Scenario: S4 deleting a sink removes the sink and its channel
    Given an audit sink
    When  an administrator calls DELETE /api/v1/audit/sinks/{id}
    Then  the answer is 204, the sink is gone, and its channel subscriber is gone

  Scenario: S4b sink removal is atomic
    Given an audit sink
    When  deleteSink runs and removing the channel fails after the sink row was removed
    Then  the sink row is still present (the transaction rolled back)

  Scenario: S5 the database refuses a raw delete of a sink's channel
    Given an audit sink
    When  DELETE FROM webhook_subscribers WHERE id = <channel id> is executed directly
    Then  the database raises a foreign-key violation and both rows remain

  Scenario: S6 the listing marks sink channels
    Given an audit sink "siem-<n>" and a lifecycle subscriber
    When  GET /api/v1/webhooks
    Then  the channel carries auditSink = "siem-<n>", and the lifecycle subscriber carries no auditSink

  Scenario: S7 the Webhooks page does not present a sink as a subscriber
    Given the listing contains a lifecycle subscriber and a sink channel "siem"
    When  an administrator opens the Webhooks page
    Then  Subscribers lists the lifecycle subscriber only, and no Delete control names "siem"

  Scenario: S8 a sink's deliveries stay visible, labelled
    Given a delivery attempt whose subscriber is the sink channel "siem"
    When  the Webhooks page is shown
    Then  that row names "siem · audit sink" and links to /integrations/sinks
```

## Must NOT

- No existing SVC test is weakened or deleted, in particular SVC_GW_AUDIT_0004
  and SVC_GW_ESTATE_000x.
- `GET /api/v1/webhooks` still lists every subscriber: the API does not hide
  channels.
- The API contract stays additive: no removed or retyped field, no changed
  success status.
- `POST /api/v1/webhooks` and sink creation behave as before.
- The estate still never deletes anything.

## Gauntlet plan

- **Full suite, types, lint:** `./mvnw clean verify`, `pnpm test:stories`,
  `pnpm e2e`, reqstool, `openspec validate`, `mkdocs build --strict`.
- **RED:** each of S1, S3, S5 and S6 is run against the unfixed code and
  observed failing; S2 and S4 are regression armor, and each is proven
  non-vacuous with a throwaway mutant.
- **Manual mutants**, each of which must be killed:
  1. the controller guard removed;
  2. the estate guard removed;
  3. FK back to CASCADE;
  4. `deleteSink` without its transaction;
  5. `auditSink` always null;
  6. the portal filter removed.
- **Adversarial pass:** a sink whose name is also used in a subscriber's event
  list; deleting the sink twice concurrently; deleting a channel id that does
  not exist (still 404).
- **Coverage:** skipped. There is no coverage tool in the build; mutation
  stands in for it, and this is recorded in EVIDENCE.
- **Independent verification:** not performed unless requested.

## Revisions

- (none)
