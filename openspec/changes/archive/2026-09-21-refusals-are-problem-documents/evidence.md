# Evidence: refusals-are-problem-documents

One fresh run of every gate after the last edit. Commit `29ade432`.

| Gate | Result |
| --- | --- |
| `./mvnw clean verify` | `Tests run: 697, Failures: 0, Errors: 0, Skipped: 9`; `Tests 104 passed (104)`; BUILD SUCCESS — green on the first run |
| `pnpm test:stories` | `Tests 45 passed (45)` |
| `E2E_GATEWAY_PORT=18500 pnpm e2e` | `20 passed (1.1m)` |
| `reqstool status local -p docs/reqstool` | `248/248 complete · 0 incomplete · PASS` |
| `openspec validate --all --strict` | `31 passed, 0 failed` |
| `mkdocs build --strict` | built clean |

## The defect, proven before it was fixed

`ErrorContractTests` was written and run first. Two of its four cases failed, and
the failure message is the finding:

```
ErrorContractTests.a_response_status_exception_is_a_problem_document_carrying_its_reason:45 Content type not set
ErrorContractTests.a_malformed_body_is_a_problem_document_as_well:73 Content type not set
```

**"Content type not set"**, not a wrong content type — the body was empty. The review
had inferred Boot's `{timestamp,status,error,path}` with the reason dropped; the
reality was that nothing was rendered at all. The other two cases passed unchanged,
which located the defect precisely: `@ExceptionHandler` was already correct, and the
inconsistency was between mechanisms.

After `spring.mvc.problemdetails.enabled: true`: `Tests run: 4, Failures: 0`.

## The document, measured before and after

Before:

```
non-success responses: 106  referencing a non-ProblemDetail schema: 96  with no content: 10
ProblemDetail component present: False
```

After:

```
4xx/5xx as problem+json ProblemDetail: 106 | wrong: 0 | no content: 0
ProblemDetail component present: True
```

## Notes

- **reqstool is 248/248 again**, up from PR C's 247: `GW_API_0007` replaces the
  retired `GW_AUTH_0027` in the count. The two are unrelated; the numbers coinciding
  is a coincidence worth not reading anything into.
- **Stacked on PR C.** The paths these tests exercise are `/api/v1/…`, which exist
  only on that branch. Reviewing this diff against `main` will show C's changes too.
- e2e ran on port 18500 for the reason recorded in PR A's evidence.
