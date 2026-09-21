# Tasks: refusals-are-problem-documents

## 1. Prove the defect first

- [x] 1.1 `ErrorContractTests` written before any fix, with four cases covering three
      distinct refusal mechanisms.
- [x] 1.2 Run it and record what the wire actually carried: **"Content type not set"**
      on two of four — an empty body, not Boot's default keys. The review's inference
      was directionally right and specifically wrong.
- [x] 1.3 Confirm the two `@ExceptionHandler` cases already passed, so the fix is
      about agreement between mechanisms.

## 2. The runtime

- [x] 2.1 `spring.mvc.problemdetails.enabled: true`, with the reason in a comment at
      the setting rather than only in the commit.
- [x] 2.2 Re-run: 4/4.

## 3. The document

- [x] 3.1 `refusalsAreProblemDocuments()` customizer: every 4xx/5xx becomes
      `application/problem+json` referencing `ProblemDetail`.
- [x] 3.2 Declare the `ProblemDetail` schema — nothing returns the type from a mapped
      method, so springdoc cannot reflect it.
- [x] 3.3 Keep every hand-written `@ApiResponse` description.
- [x] 3.4 Verify programmatically: 106 of 106 non-success responses, 0 wrong, 0
      without content.
- [x] 3.5 Correct the unversioned paths in the document's own quick-start prose, which
      PR C's sweep did not match.

## 4. Requirement and docs

- [x] 4.1 Mint `GW_API_0007` and `SVC_GW_API_0007`; confirm the id was free in
      `requirements.yml` and in every in-flight change.
- [x] 4.2 `reference/api/index.md`: the media type, the `instance` field, and which
      field a client should show a user.

## 5. Gates

- [x] 5.1 `./mvnw clean verify` — 697 Java, 104 UI, green first run.
- [x] 5.2 reqstool 248/248 · PASS.
- [x] 5.3 `mkdocs build --strict`, `openspec validate --all --strict`.
- [ ] 5.4 Story tests and e2e.
- [ ] 5.5 `evidence.md`, tracker, archive.
