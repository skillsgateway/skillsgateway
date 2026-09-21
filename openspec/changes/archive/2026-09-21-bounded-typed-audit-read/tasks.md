# Tasks: bounded-typed-audit-read

## 1. The read

- [x] 1.1 `FetchLogRepository.entriesBefore(before, limit)` — descending, `id < before`,
      `before = 0` meaning newest.
- [x] 1.2 Reuse `AuditEntry`; add no second row shape for the same rows.
- [x] 1.3 Keep `list()` for tests, with its non-request-path status on the method.

## 2. The endpoint

- [x] 2.1 Move it from `AdminController` to `AuditController`; the path is unchanged.
- [x] 2.2 `{entries, nextBefore}`, `before` and `limit`.
- [x] 2.3 `nextBefore` null on a short page, never pointing at nothing.
- [x] 2.4 Clamp to the export's bounds; add no configuration leaf.
- [x] 2.5 Correct the description, which claimed portal actions were absent from a
      ledger that `AdminAuditLogger` writes to.

## 3. Requirement

- [x] 3.1 Mint `GW_AUDIT_0008` and `SVC_GW_AUDIT_0008`; confirm the id was free.
- [x] 3.2 State in the rationale why sequence paging rather than offset, so the next
      author does not "simplify" it.

## 4. Tests

- [x] 4.1 The cursor walks strictly older with nothing repeated across two pages.
- [x] 4.2 The final page omits the cursor.
- [x] 4.3 An over-large `limit` is clamped.
- [x] 4.4 The entries are typed — `actor_type`, `token_id` and `credential_kind` are
      asserted *absent*, since their presence was the defect.
- [x] 4.5 A caller with no ledger role is refused. Covered by `RoleEnforcementTests`'s
      derived route table, which lists `GET /api/v1/audit` in its role-gated read set —
      confirmed rather than assumed.

## 5. Portal and docs

- [x] 5.1 `useAudit` unwraps `entries`; msw handlers and the three audit page tests
      return the envelope.
- [x] 5.2 `reference/api/audit.md` — envelope, parameters, and why the direction differs
      from the export.

## 6. Gates

- [x] 6.1 `./mvnw clean verify` — 701 Java, 104 UI.
- [x] 6.2 reqstool 249/249 · PASS; `mkdocs build --strict`; `openspec validate`.
- [x] 6.3 Story tests 45 passed; e2e 20 passed.
- [ ] 6.4 `evidence.md`, tracker, archive.
