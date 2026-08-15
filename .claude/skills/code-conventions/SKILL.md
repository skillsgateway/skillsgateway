---
name: code-conventions
description: Skills Gateway build commands, quality gates, Java and TypeScript conventions, and reqstool traceability annotations. Load before writing or reviewing any code in this repo.
---

# Code conventions

## Build & gates

```bash
./mvnw clean verify                     # everything: Java tests (Arconia/Testcontainers PostgreSQL),
                                        # Spotless, Checkstyle, CycloneDX SBOM, UI gates, packaged jar
(cd src/main/frontend && pnpm e2e)    # Playwright vs the real jar + mock OIDC IdP (compose.e2e.yaml)
reqstool status local -p docs/reqstool  # must end "N/N complete · PASS" (run after the two above)
openspec validate --all --strict
./mvnw -Pnative -DskipTests native:compile   # GraalVM native binary (release path; CI does this)
```

- Always `clean` before trusting the reqstool gate: the annotation processor
  writes per-source-set files that incremental compilation truncates.
- The UI is built INSIDE `./mvnw verify` (frontend-maven-plugin, pinned
  node/pnpm); `cd src/main/frontend && pnpm …` is only for UI development loops and e2e.
- `./mvnw -q spotless:apply` before committing Java.

## Java

- Java 25, Spring Boot 4. Constructor injection, no Lombok, records for DTOs.
- Persistence via `JdbcClient` + Flyway (no JPA). Single `V1__init.sql` until
  the owner says otherwise — fold schema changes into it (Testcontainers
  recreate the schema every run).
- JGit for all git operations — never subprocess git in production code.
  Tests may run the git binary only via `AbstractGatewayTest.git(...)`
  (isolated from host config).
- Formatting is Spotless (palantir-java-format) + Checkstyle — non-negotiable
  gates, auto-fix with `spotless:apply`.
- REST errors: `ResponseStatusException` / `ProblemDetail`. New endpoints get
  `@Tag`, `@Operation`, `@ApiResponse`, and `@Schema` on their DTOs (the
  Scalar reference at `/docs` renders them).

## TypeScript / UI (`src/main/frontend/`)

- Strict TS, oxlint, vitest (+ Storybook story tests with axe-as-error),
  Playwright e2e. `pnpm verify` runs the whole UI gate.
- API types are GENERATED: `src/main/frontend/openapi.json` → `src/api/types.gen.ts`
  (`pnpm exec openapi-typescript openapi.json -o src/api/types.gen.ts`).
  Regenerate after backend API changes (snapshot comes from
  `OpenApiDocsTests` → `target/openapi.json`). Never hand-edit.
- MSW handlers are typed from the generated types; mocks never appear in the
  acceptance path (Playwright runs against the real gateway).

## Traceability (reqstool)

- SSOT: `docs/reqstool/requirements.yml` + `software_verification_cases.yml`
  (`GW_*`, `SVC_GW_*`). Follow the reqstool plugin skills' conventions.
- Java: `@Requirements({"GW_XXXX"})` on the implementing method,
  `@SVCs({"SVC_GW_XXXX"})` on the verifying test.
- TypeScript: JSDoc `@Requirements GW_XXXX` on components, `@SVCs SVC_GW_XXXX`
  above Playwright `test(...)` calls — test titles must be snake_case
  identifiers (junit-name matching).
- Never weaken or delete an existing SVC test to make a change pass.

## Git

- Conventional Commits, DCO sign-off (`git commit -s`), branches
  `<type>/<kebab-description>`, PR title = conventional commit (squash merge).
