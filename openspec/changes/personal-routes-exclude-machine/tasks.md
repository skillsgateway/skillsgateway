# Tasks: personal-routes-exclude-machine

## 1. Requirements (reqstool)

- [x] 1.1 Add GW_AUTH_0057 and SVC_GW_AUTH_0057 in `docs/reqstool/`.

## 2. Personal only (SVC_GW_AUTH_0057)

- [x] 2.1 `PersonalRoutesExcludeMachineTests`: a login named like a machine
  credential does not see it listed, gets `404` rotating or revoking it, and the
  credential still authenticates. Watch it fail first.
- [x] 2.2 The `api_scopes IS NULL` predicate in the three queries
  (`@Requirements GW_AUTH_0057`), with the token and machine-credential suites
  unchanged.

## 3. Documentation

- [x] 3.1 `reference/api/tokens.md`. Verify with `mkdocs build --strict`.

## 4. Gates and evidence

- [x] 4.1 `mutants.sh`, then every AGENTS.md gate fresh, recorded in `evidence.md`.
